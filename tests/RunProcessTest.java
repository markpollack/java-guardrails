///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ../scripts/Run.java
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Cancellation must not leave a measurement process running behind the user's back. */
public class RunProcessTest {
 static List<String> command(String action,Path root){
  return List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-cp",System.getProperty("java.class.path"),RunProcessTest.class.getName(),action,root.toString());
 }
 public static void main(String[] args)throws Exception {
  if(args.length>0&&args[0].equals("worker")){
   Path root=Path.of(args[1]);Files.writeString(root.resolve("worker.pid"),Long.toString(ProcessHandle.current().pid()));
   while(true){Files.writeString(root.resolve("heartbeat"),Long.toString(System.nanoTime()));Thread.sleep(50);}
  }
  if(args.length>0&&args[0].equals("owner")){
   Path root=Path.of(args[1]);Run.logged(command("worker",root),root,root.resolve("worker.log"));return;
  }
  Path root=Files.createTempDirectory("guardrails-process-lifetime-");Process owner=new ProcessBuilder(command("owner",root)).redirectErrorStream(true).redirectOutput(root.resolve("owner.log").toFile()).start();
  ProcessHandle worker=null;
  try{
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);Path pid=root.resolve("worker.pid");
   while((!Files.exists(pid)||Files.readString(pid).isBlank())&&System.nanoTime()<deadline)Thread.sleep(50);
   worker=ProcessHandle.of(Long.parseLong(Files.readString(pid))).orElseThrow();
   owner.destroy();if(!owner.waitFor(10,TimeUnit.SECONDS))throw new AssertionError("Owned parent did not stop");
   deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(worker.isAlive()&&System.nanoTime()<deadline)Thread.sleep(50);
   if(worker.isAlive())throw new AssertionError("Measurement child survived cancellation: "+worker.pid());
   System.out.println("PASS cancellation stops owned measurement process; evidence "+root);
  }finally{if(owner.isAlive())owner.destroyForcibly();if(worker!=null&&worker.isAlive())worker.destroyForcibly();}
 }
}
