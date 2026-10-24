param([Parameter(Mandatory=$true)][string]$Executable)
$ErrorActionPreference='Stop'
if(!$IsWindows){throw 'Windows icon resources require Windows'}
$target=(Resolve-Path -LiteralPath $Executable).Path
Add-Type -AssemblyName System.Drawing
if(-not ('LaglessIconResource' -as [type])){
Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class LaglessIconResource {
  [DllImport("kernel32.dll", EntryPoint="BeginUpdateResourceW", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern IntPtr Begin(string file, bool deleteExisting);
  [DllImport("kernel32.dll", EntryPoint="UpdateResourceW", SetLastError=true)]
  [return: MarshalAs(UnmanagedType.Bool)]
  static extern bool Update(IntPtr handle, IntPtr type, IntPtr name, ushort language, byte[] data, uint size);
  [DllImport("kernel32.dll", EntryPoint="EndUpdateResourceW", SetLastError=true)]
  [return: MarshalAs(UnmanagedType.Bool)]
  static extern bool End(IntPtr handle, bool discard);
  public static void Set(string file, byte[][] images, int[] sizes) {
    IntPtr handle=Begin(file,false);if(handle==IntPtr.Zero)throw new Win32Exception();
    bool finish=false;
    try {
      byte[] group=new byte[6+images.Length*14];group[2]=1;group[4]=(byte)images.Length;
      for(int i=0;i<images.Length;i++) {
        int at=6+i*14,id=101+i;
        group[at]=(byte)(sizes[i]==256?0:sizes[i]);group[at+1]=group[at];
        group[at+4]=1;group[at+6]=32;
        BitConverter.GetBytes(images[i].Length).CopyTo(group,at+8);
        BitConverter.GetBytes((ushort)id).CopyTo(group,at+12);
        if(!Update(handle,(IntPtr)3,(IntPtr)id,0,images[i],(uint)images[i].Length))throw new Win32Exception();
      }
      if(!Update(handle,(IntPtr)14,(IntPtr)1,0,group,(uint)group.Length))throw new Win32Exception();
      finish=true;
    } finally {if(!End(handle,!finish))throw new Win32Exception();}
  }
}
'@
}
$sizes=@(16,24,32,48,64,128,256)
$images=[System.Collections.Generic.List[byte[]]]::new()
foreach($size in $sizes){
    $bitmap=[System.Drawing.Bitmap]::new($size,$size,[System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics=[System.Drawing.Graphics]::FromImage($bitmap)
    try{
        $graphics.SmoothingMode=[System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.TextRenderingHint=[System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        $graphics.Clear([System.Drawing.Color]::Transparent)
        $edge=[Math]::Max(1,[int]($size*0.05));$radius=[Math]::Max(2,[int]($size*0.19));$span=$size-2*$edge-1
        $path=[System.Drawing.Drawing2D.GraphicsPath]::new()
        try{
            $path.AddArc($edge,$edge,$radius*2,$radius*2,180,90)
            $path.AddArc($edge+$span-$radius*2,$edge,$radius*2,$radius*2,270,90)
            $path.AddArc($edge+$span-$radius*2,$edge+$span-$radius*2,$radius*2,$radius*2,0,90)
            $path.AddArc($edge,$edge+$span-$radius*2,$radius*2,$radius*2,90,90);$path.CloseFigure()
            $brush=[System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(8,127,112))
            try{$graphics.FillPath($brush,$path)}finally{$brush.Dispose()}
        }finally{$path.Dispose()}
        $font=[System.Drawing.Font]::new('Segoe UI',[Math]::Max(8,$size*0.67),[System.Drawing.FontStyle]::Bold,[System.Drawing.GraphicsUnit]::Pixel)
        $white=[System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
        $center=[System.Drawing.StringFormat]::new();$center.Alignment=[System.Drawing.StringAlignment]::Center;$center.LineAlignment=[System.Drawing.StringAlignment]::Center
        try{$graphics.DrawString('L',$font,$white,[System.Drawing.RectangleF]::new(0,-$size*0.035,$size,$size),$center)}finally{$font.Dispose();$white.Dispose();$center.Dispose()}
        $stream=[IO.MemoryStream]::new();try{$bitmap.Save($stream,[System.Drawing.Imaging.ImageFormat]::Png);$images.Add($stream.ToArray())}finally{$stream.Dispose()}
    }finally{$graphics.Dispose();$bitmap.Dispose()}
}
[LaglessIconResource]::Set($target,$images.ToArray(),[int[]]$sizes)
