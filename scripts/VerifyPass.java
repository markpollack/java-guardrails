///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//DEPS org.jacoco:org.jacoco.core:0.8.15
//SOURCES MeasurePass.java Util.java Xml.java Declarations.java ApiArtifacts.java NoTestsProbe.java BytecodeSnapshot.java Findings.java Run.java
import java.nio.file.*;
import java.util.*;
/** Normal enforcement and plants use a fresh copy of the candidate, without measurement overlays. */
public class VerifyPass {
 public static void main(String[] args)throws Exception{
  if(args.length==1&&args[0].equals("--help")){System.out.println("VerifyPass.java TARGET KIT PRIVATE_REPO OUTPUT_DIR [installed gates...]");return;}
  MeasurePass.target=Path.of(args[0]).toAbsolutePath().normalize();MeasurePass.kit=Path.of(args[1]).toAbsolutePath().normalize();MeasurePass.repo=Path.of(args[2]).toAbsolutePath().normalize();MeasurePass.output=Path.of(args[3]).toAbsolutePath().normalize();Files.createDirectories(MeasurePass.output);MeasurePass.scratch=Files.createTempDirectory(MeasurePass.output,"candidate-");MeasurePass.copy();
  Path scratch=MeasurePass.scratch;
  Path verificationLog=MeasurePass.output.resolve("verify.log");
  Run.checked(List.of(Util.mvnw(scratch),"-B","-X","-Dstyle.color=never","-Dmaven.repo.local="+MeasurePass.repo,"clean","verify"),scratch,verificationLog);
  String verification=Files.readString(verificationLog).replaceAll("\\u001B\\[[;\\d]*m","");
  for(int i=4;i<args.length;i++)requireExecution(args[i],verification);
  for(int i=4;i<args.length;i++)Run.checked(List.of(MeasurePass.kit.resolve("jbang").toString(),MeasurePass.kit.resolve("scripts/Falsify.java").toString(),scratch.toString(),args[i]),scratch,MeasurePass.output.resolve("falsify-"+args[i]+".log"));
  System.out.println("Normal candidate build and installed-gate plants verified");
 }
 static void requireExecution(String gate,String log){
  String plugin=switch(gate){case "jacoco"->"jacoco";case "pmd","cpd"->"pmd";case "spotbugs"->"spotbugs";default->"";};
  String goal=gate.equals("cpd")?"cpd-check":"check";
  boolean ran;
  if(!plugin.isEmpty())ran=log.lines().anyMatch(line->line.matches("\\[INFO\\] --- "+plugin+":[^: ]+:"+goal+" .*"));
  else if(gate.equals("archunit"))ran=log.lines().anyMatch(line->line.matches(".*Running [^ ]*NoCyclesTest.*"));
  else ran=log.lines().anyMatch(line->line.startsWith("[DEBUG] -")&&line.contains("-Xplugin:ErrorProne")&&(!gate.equals("nullaway")||line.matches(".*-Xep:NullAway:(ERROR|WARN).*")));
  if(!ran)throw new IllegalStateException(gate+" did not execute in the normal verify lifecycle; a successful explicit-goal plant is insufficient");
 }
}
