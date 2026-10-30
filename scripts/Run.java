import java.nio.file.*;
import java.util.*;

/** Owned child-process lifetime and diagnostic hygiene, shared by pass orchestration. */
final class Run {
    static String redact(String text){
        return text.replaceAll("(?i)(password|passwd|token|secret|authorization)([=:\\s]+)[^\\s<]+", "$1$2[REDACTED]")
            .replaceAll("(https?://)[^/@\\s]+:[^/@\\s]+@", "$1[REDACTED]@");
    }
    static int logged(List<String> command,Path cwd,Path log)throws Exception{
        Files.createDirectories(log.toAbsolutePath().getParent());Files.writeString(log.resolveSibling(log.getFileName()+".argv"),redact(String.join("\n",command))+"\n");
        Process p=new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Thread hook=new Thread(()->{p.descendants().forEach(ProcessHandle::destroy);p.destroy();});Runtime.getRuntime().addShutdownHook(hook);
        long started=System.nanoTime();
        int exit;try{
            while(!p.waitFor(30,java.util.concurrent.TimeUnit.SECONDS))System.out.printf(Locale.ROOT,"Still running: %s (%.1f minutes); evidence %s%n",log.getFileName(),(System.nanoTime()-started)/60_000_000_000.0,log);
            exit=p.exitValue();
        }catch(InterruptedException e){p.descendants().forEach(ProcessHandle::destroy);p.destroy();Thread.currentThread().interrupt();throw e;}finally{Runtime.getRuntime().removeShutdownHook(hook);}
        Files.writeString(log,redact(Files.readString(log)));return exit;
    }
    static final class Failed extends Exception {
        final int exit;final List<String> command;final Path log;
        Failed(int exit,List<String> command,Path log){super("Exit "+exit+"; original diagnostic in "+log);this.exit=exit;this.command=List.copyOf(command);this.log=log;}
    }
    static void checked(List<String> command,Path cwd,Path log)throws Exception{
        int exit=logged(command,cwd,log);if(exit!=0){System.err.print(Files.readString(log));throw new Failed(exit,command,log);}
    }
    static String identity(Path root){
        try{Process p=new ProcessBuilder("git","rev-parse","HEAD").directory(root.toFile()).redirectErrorStream(true).start();String text=new String(p.getInputStream().readAllBytes());return p.waitFor()==0?text.trim():"uncommitted/archive";}catch(Exception e){return "unavailable";}
    }
    static String defect(Path target,Path kit,Path output,Exception error)throws Exception{
        String version="unknown";var m=java.util.regex.Pattern.compile("(?m)^  version: (.+)$").matcher(Files.readString(kit.resolve("SKILL.md")));if(m.find())version=m.group(1);
        StringBuilder text=new StringBuilder("STOP KIT_DEFECT (halt)\n\nThe pass stopped because a required measurement/validation could not complete. Accepted work is preserved. The cause is unclassified; a Maven failure alone is not proof of a kit bug.\n\n");
        text.append("Kit: ").append(version).append(" at ").append(kit).append(" (source ").append(identity(kit)).append(")\nTarget: ").append(target).append(" (commit ").append(identity(target)).append(")\nExpected: complete evidence for every selected module; no omitted or invented zero results.\nObserved: ").append(error.getMessage()).append("\nEvidence: ").append(output).append('\n');
        Path state=target.resolve(".guardrails-run/state.json");if(Files.isRegularFile(state)){
            String json=Files.readString(state);
            for(String key:List.of("start","lastCommit")){
                var field=java.util.regex.Pattern.compile("\""+key+"\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                if(field.find())text.append(key.equals("start")?"Original start: ":"Last accepted commit: ").append(field.group(1)).append('\n');
            }
            text.append("Run state: ").append(state).append("; preserve this record.\n");
        }
        if(error instanceof Failed failed){
            text.append("Exit: ").append(failed.exit).append("\nCommand/profile (exact argv, sanitized):\n").append(String.join("\n",failed.command)).append("\nOriginal diagnostic excerpt:\n");
            var lines=Files.readAllLines(failed.log);text.append(String.join("\n",lines.subList(Math.max(0,lines.size()-60),lines.size()))).append('\n');
        }
        text.append("\nReproduce using the saved argv file beside the failing log, against this unchanged candidate. Fix the measurement and rerun from the same original start. Diagnostic workarounds cannot certify completion.\n");
        return redact(text.toString());
    }

}
