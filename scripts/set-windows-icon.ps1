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
        $brush=[System.Drawing.Drawing2D.LinearGradientBrush]::new([System.Drawing.PointF]::new(0,0),[System.Drawing.PointF]::new($size,$size),[System.Drawing.Color]::FromArgb(41,38,63),[System.Drawing.Color]::FromArgb(22,27,39))
            try{$graphics.FillPath($brush,$path)}finally{$brush.Dispose()}
        }finally{$path.Dispose()}
        $stroke=[Math]::Max(1.5,$size*0.085)
        $violet=[System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(169,147,255),$stroke)
        $light=[System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(240,236,255),$stroke)
        try{
            foreach($pen in @($violet,$light)){$pen.StartCap=[System.Drawing.Drawing2D.LineCap]::Round;$pen.EndCap=[System.Drawing.Drawing2D.LineCap]::Round;$pen.LineJoin=[System.Drawing.Drawing2D.LineJoin]::Round}
            $graphics.DrawLine($violet,$size*7/32,$size*9/32,$size*14/32,$size/2)
            $graphics.DrawLine($violet,$size*14/32,$size/2,$size*7/32,$size*23/32)
            $graphics.DrawLine($light,$size*17/32,$size*23/32,$size*25/32,$size*23/32)
        }finally{$violet.Dispose();$light.Dispose()}
        $stream=[IO.MemoryStream]::new();try{$bitmap.Save($stream,[System.Drawing.Imaging.ImageFormat]::Png);$images.Add($stream.ToArray())}finally{$stream.Dispose()}
    }finally{$graphics.Dispose();$bitmap.Dispose()}
}
[LaglessIconResource]::Set($target,$images.ToArray(),[int[]]$sizes)
