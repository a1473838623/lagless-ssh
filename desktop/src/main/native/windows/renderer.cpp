// Lagless HWND renderer. Uses the system DirectWrite font engine and Direct2D
// frame presentation, independently of AWT's Java2D rendering pipeline.
#include <windows.h>
#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>
#include <d2d1.h>
#include <dwrite.h>
#include <wrl/client.h>
#include <vector>
#include <string>
#include <unordered_map>
#include <cmath>
#include <cwchar>
#include <algorithm>
#include <restartmanager.h>
#include <wincrypt.h>
#pragma comment(lib, "Rstrtmgr.lib")
#pragma comment(lib, "Crypt32.lib")
using Microsoft::WRL::ComPtr;
static thread_local std::string lastError;
static bool check(HRESULT result,const char* operation){
    if(SUCCEEDED(result))return true;
    char message[160];sprintf_s(message,"%s (0x%08lx)",operation,(unsigned long)result);lastError=message;return false;
}
static jbyteArray protectBytes(JNIEnv* env,jbyteArray input,bool decrypt){
    if(!input)return nullptr;
    jsize length=env->GetArrayLength(input);std::vector<BYTE> bytes(length);
    if(length>0)env->GetByteArrayRegion(input,0,length,reinterpret_cast<jbyte*>(bytes.data()));
    if(env->ExceptionCheck())return nullptr;
    DATA_BLOB source{static_cast<DWORD>(bytes.size()),bytes.data()},result{};
    BOOL okay=decrypt?CryptUnprotectData(&source,nullptr,nullptr,nullptr,nullptr,CRYPTPROTECT_UI_FORBIDDEN,&result):
        CryptProtectData(&source,L"Lagless AI credential",nullptr,nullptr,nullptr,CRYPTPROTECT_UI_FORBIDDEN,&result);
    SecureZeroMemory(bytes.data(),bytes.size());
    if(!okay){lastError=decrypt?"Windows credential decryption failed":"Windows credential encryption failed";return nullptr;}
    jbyteArray output=env->NewByteArray(static_cast<jsize>(result.cbData));
    if(output)env->SetByteArrayRegion(output,0,static_cast<jsize>(result.cbData),reinterpret_cast<const jbyte*>(result.pbData));
    LocalFree(result.pbData);return output;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_lagless_desktop_NativeGraphics_protectSecret(JNIEnv* env,jclass,jbyteArray input){return protectBytes(env,input,false);}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_lagless_desktop_NativeGraphics_unprotectSecret(JNIEnv* env,jclass,jbyteArray input){return protectBytes(env,input,true);}
// Query only: never shut down applications or services through Restart Manager.
extern "C" JNIEXPORT jlongArray JNICALL Java_dev_lagless_desktop_NativeGraphics_fileUsers(JNIEnv* env,jclass,jobjectArray files){
    DWORD session=0;WCHAR key[CCH_RM_SESSION_KEY+1]{};
    DWORD code=RmStartSession(&session,0,key);
    if(!check(HRESULT_FROM_WIN32(code),"Restart Manager session"))return nullptr;
    struct SessionEnd {DWORD handle;~SessionEnd(){RmEndSession(handle);}} end{session};
    try{
        jsize count=env->GetArrayLength(files);std::vector<std::wstring> names;names.reserve(count);
        for(jsize i=0;i<count;i++){
            jstring value=static_cast<jstring>(env->GetObjectArrayElement(files,i));if(!value)return nullptr;
            jsize length=env->GetStringLength(value);std::wstring name(length,L'\0');
            env->GetStringRegion(value,0,length,reinterpret_cast<jchar*>(name.data()));env->DeleteLocalRef(value);
            if(env->ExceptionCheck())return nullptr;names.push_back(std::move(name));
        }
        std::vector<LPCWSTR> paths;for(auto& name:names)paths.push_back(name.c_str());
        code=RmRegisterResources(session,static_cast<UINT>(paths.size()),paths.data(),0,nullptr,0,nullptr);
        if(!check(HRESULT_FROM_WIN32(code),"Register installation files"))return nullptr;
        std::vector<RM_PROCESS_INFO> users;UINT needed=0,size=0;DWORD reasons=0;
        for(int attempt=0;attempt<4;attempt++){
            size=static_cast<UINT>(users.size());code=RmGetList(session,&needed,&size,users.empty()?nullptr:users.data(),&reasons);
            if(code!=ERROR_MORE_DATA)break;users.resize(needed);
        }
        if(!check(HRESULT_FROM_WIN32(code),"Query installation file users"))return nullptr;
        std::vector<jlong> pids;for(UINT i=0;i<size;i++)pids.push_back(users[i].Process.dwProcessId);
        jlongArray result=env->NewLongArray(static_cast<jsize>(pids.size()));
        if(result&&!pids.empty())env->SetLongArrayRegion(result,0,static_cast<jsize>(pids.size()),pids.data());return result;
    }catch(...){lastError="Cannot query installation file users";return nullptr;}
}
// Windows may run an idle or background window's process in efficiency mode
// (EcoQoS) and coarsen its timers, so the terminal feels slow when the user
// comes back. A terminal must answer keys and frames at once: opt out.
static void keepFullSpeed(){
    static bool done=false;if(done)return;done=true;
    struct Throttling {ULONG Version,ControlMask,StateMask;};
    using SetInformation=BOOL(WINAPI*)(HANDLE,int,LPVOID,DWORD);
    auto set=reinterpret_cast<SetInformation>(GetProcAddress(GetModuleHandleW(L"kernel32.dll"),"SetProcessInformation"));
    if(!set)return; // Before Windows 8 there is no process throttling to opt out of.
    // ProcessPowerThrottling = 4; EXECUTION_SPEED = 1, IGNORE_TIMER_RESOLUTION = 4.
    Throttling state{1,0x1|0x4,0};
    if(!set(GetCurrentProcess(),4,&state,sizeof state)){state.ControlMask=0x1;set(GetCurrentProcess(),4,&state,sizeof state);}
}
struct Renderer {
    HWND window=nullptr;
    ComPtr<ID2D1Factory> factory;
    ComPtr<IDWriteFactory> write;
    ComPtr<ID2D1HwndRenderTarget> target;
    ComPtr<ID2D1SolidColorBrush> brush;
    ComPtr<IDWriteTextFormat> format;
    ComPtr<IDWriteFontFace> face;
    ComPtr<IDWriteRenderingParams> rendering;
    struct GlyphLayout {ComPtr<IDWriteTextLayout> layout;float baseline,width;};
    std::unordered_map<unsigned int,GlyphLayout> layouts;
    std::vector<jint> chars,fg,bg;
    std::vector<UINT16> indices;
    std::vector<FLOAT> advances;
    UINT16 ascii[128]{};
    float fontSize=0,emSize=0,capHeight=0;
    int failures=0;ULONGLONG failingSince=0;
    void lose(){brush.Reset();rendering.Reset();target.Reset();}
    bool initialize(HWND hwnd){
        window=hwnd;
        return check(D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED,factory.GetAddressOf()),"Direct2D factory")
            &&check(DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED,__uuidof(IDWriteFactory),reinterpret_cast<IUnknown**>(write.GetAddressOf())),"DirectWrite factory");
    }
    bool size(UINT width,UINT height){
        if(!target){
            auto properties=D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_DEFAULT,D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM,D2D1_ALPHA_MODE_IGNORE),96,96);
            // Keep the last presented image during ancestor-window exposure.
            // Frames are still fully repainted; retaining is not pixel scrolling.
            if(!check(factory->CreateHwndRenderTarget(properties,D2D1::HwndRenderTargetProperties(window,D2D1::SizeU(width,height),D2D1_PRESENT_OPTIONS_RETAIN_CONTENTS),target.GetAddressOf()),"Direct2D HWND target"))return false;
            target->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_CLEARTYPE);
            if(!check(write->CreateRenderingParams(rendering.ReleaseAndGetAddressOf()),"ClearType parameters"))return false;
            target->SetTextRenderingParams(rendering.Get());
            if(!check(target->CreateSolidColorBrush(D2D1::ColorF(1,1,1),brush.ReleaseAndGetAddressOf()),"Direct2D brush"))return false;
        }else{
            auto current=target->GetPixelSize();
            if((current.width!=width||current.height!=height)&&!check(target->Resize(D2D1::SizeU(width,height)),"Direct2D resize"))return false;
        }
        return true;
    }
    bool font(float requested){
        if(format&&fontSize==requested)return true;
        layouts.clear();fontSize=requested;
        ComPtr<IDWriteFontCollection> fonts;ComPtr<IDWriteFontFamily> family;ComPtr<IDWriteFont> chosen;
        if(!check(write->GetSystemFontCollection(fonts.GetAddressOf()),"System fonts"))return false;
        UINT32 index=0;BOOL exists=FALSE;const wchar_t* name=L"Consolas";
        fonts->FindFamilyName(name,&index,&exists);
        if(!exists){name=L"Courier New";fonts->FindFamilyName(name,&index,&exists);}
        if(!exists){index=0;name=L"monospace";}
        if(!check(fonts->GetFontFamily(index,family.GetAddressOf()),"Monospace font")||
           !check(family->GetFirstMatchingFont(DWRITE_FONT_WEIGHT_NORMAL,DWRITE_FONT_STRETCH_NORMAL,DWRITE_FONT_STYLE_NORMAL,chosen.GetAddressOf()),"Monospace face")||
           !check(chosen->CreateFontFace(face.ReleaseAndGetAddressOf()),"Font face")||
           !check(write->CreateTextFormat(name,nullptr,DWRITE_FONT_WEIGHT_NORMAL,DWRITE_FONT_STYLE_NORMAL,DWRITE_FONT_STRETCH_NORMAL,requested,L"zh-CN",format.ReleaseAndGetAddressOf()),"Text format"))return false;
        format->SetWordWrapping(DWRITE_WORD_WRAPPING_NO_WRAP);
        format->SetTextAlignment(DWRITE_TEXT_ALIGNMENT_LEADING);
        format->SetParagraphAlignment(DWRITE_PARAGRAPH_ALIGNMENT_NEAR);
        UINT32 points[128];for(int i=0;i<128;i++)points[i]=i;
        face->GetGlyphIndices(points,128,ascii);emSize=requested;
        DWRITE_FONT_METRICS metrics{};face->GetMetrics(&metrics);
        capHeight=requested*(metrics.capHeight?metrics.capHeight:metrics.ascent*.75f)/std::max<UINT16>(1,metrics.designUnitsPerEm);
        return true;
    }
    void color(int rgb){brush->SetColor(D2D1::ColorF((rgb>>16&255)/255.f,(rgb>>8&255)/255.f,(rgb&255)/255.f));}
    void glyph(int cp,float x,float top,float baseline,float width,float height){
        auto found=layouts.find(cp);
        if(found==layouts.end()){
            wchar_t text[2];UINT32 length=1;
            if(cp<=0xffff)text[0]=static_cast<wchar_t>(cp);
            else{cp-=0x10000;text[0]=static_cast<wchar_t>(0xd800+(cp>>10));text[1]=static_cast<wchar_t>(0xdc00+(cp&1023));length=2;cp+=0x10000;}
            ComPtr<IDWriteTextLayout> layout;
            if(FAILED(write->CreateTextLayout(text,length,format.Get(),4096,4096,layout.GetAddressOf())))return;
            DWRITE_LINE_METRICS line{};UINT32 count=0;layout->GetLineMetrics(&line,1,&count);
            DWRITE_TEXT_METRICS metrics{};layout->GetMetrics(&metrics);
            if(layouts.size()>=2048)layouts.clear();
            found=layouts.emplace(cp,GlyphLayout{layout,line.baseline,std::max(1.f,metrics.widthIncludingTrailingWhitespace)}).first;
        }
        auto clip=D2D1::RectF(x,top,x+width,top+height);
        target->PushAxisAlignedClip(clip,D2D1_ANTIALIAS_MODE_ALIASED);
        float natural=found->second.width,scale=std::min(1.f,width/natural);
        float left=x+(width-natural*scale)/2;
        D2D1_MATRIX_3X2_F previous;target->GetTransform(&previous);
        target->SetTransform(D2D1::Matrix3x2F::Scale(scale,1,D2D1::Point2F(left,baseline))*previous);
        target->DrawTextLayout(D2D1::Point2F(left,baseline-found->second.baseline),found->second.layout.Get(),brush.Get(),D2D1_DRAW_TEXT_OPTIONS_ENABLE_COLOR_FONT);
        target->SetTransform(previous);
        target->PopAxisAlignedClip();
    }
    bool paint(JNIEnv* env,int logicalWidth,int logicalHeight,int columns,int rows,jintArray characters,jintArray foreground,jintArray background,
               int cellWidth,int cellHeight,int ascent,int offset,int cursorColumn,int cursorRow,bool cursor,float requested,int paper){
        if(columns<1||rows<1||columns>32768||rows>32768||static_cast<long long>(columns)*rows>4000000){lastError="Invalid frame dimensions";return false;}
        int length=columns*rows;
        if(env->GetArrayLength(characters)!=length||env->GetArrayLength(foreground)!=length||env->GetArrayLength(background)!=length){lastError="Invalid frame arrays";return false;}
        chars.resize(length);fg.resize(length);bg.resize(length);
        env->GetIntArrayRegion(characters,0,length,chars.data());env->GetIntArrayRegion(foreground,0,length,fg.data());env->GetIntArrayRegion(background,0,length,bg.data());
        if(env->ExceptionCheck())return false;
        RECT bounds;GetClientRect(window,&bounds);
        // Minimized or hidden: keep the target at its real size. Shrinking it to
        // 1x1 here left a black window after restore until the next full frame.
        if(IsIconic(GetAncestor(window,GA_ROOT))||bounds.right<1||bounds.bottom<1)return true;
        UINT w=bounds.right,h=bounds.bottom;
        if(!size(w,h))return recover();
        float sx=w/static_cast<float>(std::max(1,logicalWidth)),sy=h/static_cast<float>(std::max(1,logicalHeight));
        if(!font(requested*sy))return false;
        // Snap the grid to physical pixels. No scaling of rasterized text and no
        // copy of the previous frame; the swap/present is owned by Direct2D/DWM.
        float cw=cellWidth*sx,ch=cellHeight*sy,base=std::round(ascent*sy),shift=std::round(offset*sy);
        target->BeginDraw();target->SetTransform(D2D1::Matrix3x2F::Identity());
        target->Clear(D2D1::ColorF(paper&0xffffff));
        for(int row=0;row<rows;row++){
            float top=row*ch-shift;if(top>=h||top+ch<=0)continue;
            int start=row*columns;
            for(int col=0;col<columns;){int end=col+1;while(end<columns&&bg[start+end]==bg[start+col])end++;
                color(bg[start+col]);target->FillRectangle(D2D1::RectF(col*cw,top,end*cw,top+ch),brush.Get());col=end;}
            target->PushAxisAlignedClip(D2D1::RectF(0,top,static_cast<float>(w),top+ch),D2D1_ANTIALIAS_MODE_ALIASED);
            for(int col=0;col<columns;){
                int cp=chars[start+col];if(cp<33){col++;continue;}
                color(fg[start+col]);
                if(cp<127){
                    int end=col+1;while(end<columns&&chars[start+end]>=32&&chars[start+end]<127&&fg[start+end]==fg[start+col])end++;
                    indices.resize(end-col);advances.assign(end-col,cw);
                    for(int n=col;n<end;n++)indices[n-col]=ascii[chars[start+n]];
                    DWRITE_GLYPH_RUN run{};run.fontFace=face.Get();run.fontEmSize=emSize;run.glyphCount=end-col;run.glyphIndices=indices.data();run.glyphAdvances=advances.data();
                    target->DrawGlyphRun(D2D1::Point2F(col*cw,top+base),&run,brush.Get(),DWRITE_MEASURING_MODE_NATURAL);col=end;
                }else{int span=col+1<columns&&chars[start+col+1]<0?2:1;glyph(cp,col*cw,top,top+base,span*cw,ch);col+=span;}
            }
            target->PopAxisAlignedClip();
        }
        if(cursor&&cursorRow>=0&&cursorColumn>=0){
            color(0xb5d8d0);float x=std::round(cursorColumn*cw+std::max(1.f,sx));
            float rowTop=std::round(cursorRow*ch-shift);
            int rowHeight=std::max(1,static_cast<int>(std::round((cursorRow+1)*ch-shift)-rowTop));
            int height=std::min(rowHeight,std::max(2,static_cast<int>(std::round(capHeight+6*sy))));
            // The row includes descent/leading below the prompt. Align the bar
            // with visible text around the actual baseline, in physical pixels.
            float top=std::clamp(std::round(cursorRow*ch-shift+base-capHeight/2.f-height/2.f),rowTop,rowTop+rowHeight-height);
            target->FillRectangle(D2D1::RectF(x,top,x+std::max(1.f,std::round(2*sx)),top+height),brush.Get());
        }
        HRESULT result=target->EndDraw();
        if(SUCCEEDED(result)){failures=0;return true;}
        check(result,"Direct2D present");
        return recover();
    }
    // Sleep, screen lock, remote desktop or a driver update can remove the
    // GPU device. Rebuild the target and repaint instead of leaving a black
    // window or dropping to the slow compatible renderer; give up only when
    // rebuilding keeps failing.
    bool recover(){
        lose();
        ULONGLONG now=GetTickCount64();
        if(failures++==0)failingSince=now;
        // A device can stay unavailable while the screen is locked; the Java
        // side repaints periodically, so each try is cheap and never spins.
        return failures<=8||now-failingSince<60000;
    }
};
extern "C" JNIEXPORT jlong JNICALL Java_dev_lagless_desktop_NativeGraphics_open(JNIEnv* env,jclass,jobject canvas){
    try{
        JAWT awt{};awt.version=JAWT_VERSION_1_4;
        HMODULE module=GetModuleHandleW(L"jawt.dll");if(!module)module=LoadLibraryW(L"jawt.dll");
        auto get=module?reinterpret_cast<jboolean(JNICALL*)(JNIEnv*,JAWT*)>(GetProcAddress(module,"JAWT_GetAWT")):nullptr;
        if(!get||!get(env,&awt)){lastError="JAWT native window access unavailable";return 0;}
        JAWT_DrawingSurface* surface=awt.GetDrawingSurface(env,canvas);
        if(!surface){lastError="Canvas has no native drawing surface";return 0;}
        jint locked=surface->Lock(surface);HWND hwnd=nullptr;
        if(!(locked&JAWT_LOCK_ERROR)){
            auto info=surface->GetDrawingSurfaceInfo(surface);
            if(info){hwnd=static_cast<JAWT_Win32DrawingSurfaceInfo*>(info->platformInfo)->hwnd;surface->FreeDrawingSurfaceInfo(info);}
            surface->Unlock(surface);
        }
        awt.FreeDrawingSurface(surface);
        if(!hwnd||!IsWindow(hwnd)){lastError="Canvas HWND unavailable";return 0;}
        auto renderer=new Renderer();if(!renderer->initialize(hwnd)){delete renderer;return 0;}
        keepFullSpeed();
        return reinterpret_cast<jlong>(renderer);
    }catch(...){lastError="Native display initialization failed";return 0;}
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_lagless_desktop_NativeGraphics_draw(JNIEnv* env,jclass,jlong handle,jint width,jint height,jint cols,jint rows,jintArray chars,jintArray fg,jintArray bg,jint cw,jint ch,jint ascent,jint offset,jint cx,jint cy,jboolean cursor,jfloat font,jint paper){
    try{return handle&&reinterpret_cast<Renderer*>(handle)->paint(env,width,height,cols,rows,chars,fg,bg,cw,ch,ascent,offset,cx,cy,cursor,font,paper);}
    catch(...){lastError="Native display frame failed";return JNI_FALSE;}
}
extern "C" JNIEXPORT void JNICALL Java_dev_lagless_desktop_NativeGraphics_dispose(JNIEnv*,jclass,jlong handle){delete reinterpret_cast<Renderer*>(handle);}
extern "C" JNIEXPORT jstring JNICALL Java_dev_lagless_desktop_NativeGraphics_error(JNIEnv* env,jclass){return env->NewStringUTF(lastError.c_str());}

// CF_UNICODETEXT and Java strings both contain UTF-16 code units. Do not pass
// user text through NewStringUTF, ANSI code pages, or AWT flavor conversion.
static thread_local std::string clipboardFailure;
static constexpr SIZE_T maxClipboardUnits=16*1024*1024;
struct ClipboardAccess {
    bool opened=false;
    explicit ClipboardAccess(HWND owner){
        for(int attempt=0;attempt<6;attempt++){
            if(OpenClipboard(owner)){opened=true;break;}
            if(attempt<5)Sleep(10);
        }
        if(!opened)clipboardFailure="Clipboard is busy; please retry";
    }
    ~ClipboardAccess(){if(opened)CloseClipboard();}
};
struct ClipboardLock {
    HGLOBAL handle;const WCHAR* text;
    explicit ClipboardLock(HGLOBAL data):handle(data),text(static_cast<const WCHAR*>(GlobalLock(data))){}
    ~ClipboardLock(){if(text)GlobalUnlock(handle);}
};
extern "C" JNIEXPORT jstring JNICALL Java_dev_lagless_desktop_NativeGraphics_readClipboardText(JNIEnv* env,jclass){
    static_assert(sizeof(WCHAR)==sizeof(jchar),"Windows and JNI UTF-16 units must match");
    clipboardFailure.clear();
    try{
        ClipboardAccess access(nullptr);if(!access.opened)return nullptr;
        // AWT can defer rendering until its event thread handles WM_RENDERFORMAT.
        // Calling that path synchronously from the same EDT would deadlock. Its
        // cached Java Transferable already contains the original Unicode string.
        HWND owner=GetClipboardOwner();DWORD process=0;WCHAR className[64]{};
        if(owner)GetWindowThreadProcessId(owner,&process);
        if(process==GetCurrentProcessId()&&GetClassNameW(owner,className,64)&&wcsncmp(className,L"SunAwt",6)==0){clipboardFailure="JAVA_LOCAL_CLIPBOARD";return nullptr;}
        if(!IsClipboardFormatAvailable(CF_UNICODETEXT))return nullptr;
        HGLOBAL data=static_cast<HGLOBAL>(GetClipboardData(CF_UNICODETEXT));
        if(!data){clipboardFailure="Cannot read Unicode clipboard text";return nullptr;}
        SIZE_T bytes=GlobalSize(data);
        if(bytes<sizeof(WCHAR)||bytes%sizeof(WCHAR)||bytes/sizeof(WCHAR)>maxClipboardUnits+1){clipboardFailure="Invalid or oversized Unicode clipboard text";return nullptr;}
        ClipboardLock memory(data);if(!memory.text){clipboardFailure="Cannot lock clipboard text";return nullptr;}
        SIZE_T capacity=bytes/sizeof(WCHAR),length=0;
        while(length<capacity&&memory.text[length]!=L'\0')length++;
        if(length==capacity){clipboardFailure="Unicode clipboard text has no terminator";return nullptr;}
        return env->NewString(reinterpret_cast<const jchar*>(memory.text),static_cast<jsize>(length));
    }catch(...){clipboardFailure="Cannot read Unicode clipboard text";return nullptr;}
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_lagless_desktop_NativeGraphics_writeClipboardText(JNIEnv* env,jclass,jstring value){
    clipboardFailure.clear();
    if(!value){clipboardFailure="Clipboard text is null";return JNI_FALSE;}
    jsize length=env->GetStringLength(value);
    if(static_cast<SIZE_T>(length)>maxClipboardUnits){clipboardFailure="Clipboard text is too large";return JNI_FALSE;}
    HGLOBAL data=GlobalAlloc(GMEM_MOVEABLE|GMEM_ZEROINIT,(static_cast<SIZE_T>(length)+1)*sizeof(WCHAR));
    if(!data){clipboardFailure="Cannot allocate clipboard text";return JNI_FALSE;}
    WCHAR* text=static_cast<WCHAR*>(GlobalLock(data));
    if(!text){GlobalFree(data);clipboardFailure="Cannot lock clipboard text";return JNI_FALSE;}
    env->GetStringRegion(value,0,length,reinterpret_cast<jchar*>(text));text[length]=L'\0';GlobalUnlock(data);
    if(env->ExceptionCheck()){GlobalFree(data);return JNI_FALSE;}
    // A non-null owner is required by EmptyClipboard/SetClipboardData. Keep a
    // hidden message-only window on the calling thread; never show a window.
    static thread_local HWND owner=nullptr;
    if(!owner||!IsWindow(owner))owner=CreateWindowExW(0,L"STATIC",L"Lagless Clipboard",0,0,0,0,0,HWND_MESSAGE,nullptr,GetModuleHandleW(nullptr),nullptr);
    if(!owner){GlobalFree(data);clipboardFailure="Cannot create clipboard owner";return JNI_FALSE;}
    ClipboardAccess access(owner);
    if(!access.opened){GlobalFree(data);return JNI_FALSE;}
    if(!EmptyClipboard()){GlobalFree(data);clipboardFailure="Cannot prepare clipboard";return JNI_FALSE;}
    if(!SetClipboardData(CF_UNICODETEXT,data)){GlobalFree(data);clipboardFailure="Cannot write Unicode clipboard text";return JNI_FALSE;}
    // Ownership of data now belongs to Windows; it must not be freed here.
    return JNI_TRUE;
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_lagless_desktop_NativeGraphics_clipboardError(JNIEnv* env,jclass){return env->NewStringUTF(clipboardFailure.c_str());}
extern "C" JNIEXPORT jint JNICALL Java_dev_lagless_desktop_NativeGraphics_clipboardVersion(JNIEnv*,jclass){return 1;}
