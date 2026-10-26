import java.io.*;import java.lang.reflect.*;import java.util.concurrent.atomic.AtomicLong;
public class AndroidRuntimeSmoke {
 static Object instance(String name)throws Exception{var c=Class.forName(name);var f=c.getDeclaredField("INSTANCE");f.setAccessible(true);return f.get(null);}
 static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception{var m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
 public static void main(String[] args)throws Exception{
  Object renderer=instance("com.dreamdisplays.platform.client.render.RenderBackendCompat");
  if(!Boolean.FALSE.equals(call(renderer,"canUseDirectOpenGl",new Class<?>[]{})))throw new AssertionError("Android must not use direct OpenGL PBO");
  Class<?> hw=Class.forName("com.dreamdisplays.media.player.process.HwAccelBackend");
  Object companion=hw.getField("Companion").get(null);
  if(!call(companion,"detectDefault",new Class<?>[]{}).toString().equals("NONE"))throw new AssertionError("Android selected desktop hardware decoding");
  Object ffmpeg=instance("com.dreamdisplays.media.player.process.FFmpegBinary");
  String expected=System.getenv("POJAV_FFMPEG_PATH");
  if(!new File(expected).getAbsolutePath().equals(call(ffmpeg,"getPath",new Class<?>[]{})))throw new AssertionError("Launcher FFmpeg not selected");
  var builder=(ProcessBuilder)call(ffmpeg,"processBuilder",new Class<?>[]{java.util.List.class},java.util.List.of(expected,"-version"));
  if(!builder.environment().get("LD_LIBRARY_PATH").startsWith(new File(expected).getParent()))throw new AssertionError("FFmpeg shared library path missing");
  Class<?> audio=Class.forName("com.dreamdisplays.media.player.pipeline.AndroidOpenALLine");
  var constructor=audio.getDeclaredConstructor();constructor.setAccessible(true);Object line=constructor.newInstance();
  for(var f:audio.getDeclaredFields())if(f.getName().equals("framesWritten")||f.getName().equals("framesPlayed")){f.setAccessible(true);((AtomicLong)f.get(line)).set(f.getName().equals("framesWritten")?100:25);}
  if(!Long.valueOf(25).equals(call(line,"getLongFramePosition",new Class<?>[]{})))throw new AssertionError("Audio clock advanced past consumed buffers");
  System.out.println("PASS Android runtime: command encoder, software decoder, launcher FFmpeg/dependencies, consumed-buffer audio clock");
 }
}
