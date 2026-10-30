///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ValidationPlan.java Util.java Findings.java Declarations.java Xml.java Run.java OptionalSnapshot.java ConsumerSnapshot.java

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.google.gson.GsonBuilder;

/** Local pass transactions. Recorded decisions choose policy; neither mode can bypass verification. */
public class Pass {
    static final List<String> DEFAULTS=List.of("jacoco","errorprone","spotbugs","pmd","archunit");
    static final List<String> OPTIONAL=List.of("nullaway","pit","lincheck");
    static final String DIRECTORY=".guardrails-run";
    static Path target,kit,local;
    static class State {
        int schema=1,scopeSamples,plannedHotspots;long startedMillis=System.currentTimeMillis();String finalTree,redTree;List<String> queued=new ArrayList<>();String version,start,branch,lastCommit,lastMeasurement,baseline,pendingTree,pendingMeasurement;
        List<String> required=new ArrayList<>();Map<String,String> handsOff=new TreeMap<>(),baselineHashes=new TreeMap<>();
        Map<String,String> lastHashes=new TreeMap<>(),pendingHashes=new TreeMap<>();
        List<Step> steps=new ArrayList<>();
    }
    record Step(String commit,String tree,String measurement,long elapsedMillis){}
    public static void main(String[] args)throws Exception{
        if(args.length==1&&args[0].equals("--help")){
            System.out.println("Pass.java start|baseline|estimate|check|check-sample|check-boundary|check-final|hotspot|accept|accept-batch|bisect|resume|report|finish TARGET KIT [commit message]\nstart creates an isolated local worktree; baseline captures the original start; check validates changed modules and dependents; gate boundaries and finish require full measurement; accept commits exactly the checked tree. Same kit version only. Nothing is pushed.");return;
        }
        if(args.length<3)throw new IllegalArgumentException("Use --help");
        target=Path.of(args[1]).toAbsolutePath().normalize();kit=Path.of(args[2]).toAbsolutePath().normalize();local=target.resolve(DIRECTORY);
        if(args[0].equals("start")){start();return;}
        State state=read();
        if(!state.version.equals(version()))throw new IllegalStateException("Kit version changed from "+state.version+" to "+version()+". Restart from original start "+state.start+" on a new worktree, reusing decisions.md. This run cannot resume or rebase its baseline.");
        if(!git("branch","--show-current").trim().equals(state.branch))throw new IllegalStateException("This run belongs to branch "+state.branch);
        switch(args[0]){
            case "baseline"->baseline(state);
            case "check"->check(state,false,false);
            case "check-sample"->check(state,false,false,true);
            case "check-boundary"->check(state,true,false);
            case "check-final"->check(state,true,true);
            case "hotspot"->{if(args.length!=4)throw new IllegalArgumentException("hotspot requires a commit message");queue(state,args[3]);}
            case "accept-batch"->acceptBatch(state);
            case "bisect"->bisect(state);
            case "estimate"->{if(args.length!=4)throw new IllegalArgumentException("estimate requires planned hotspot count");estimate(state,Integer.parseInt(args[3]));}
            case "accept"->{if(args.length!=4)throw new IllegalArgumentException("accept requires one commit message argument");accept(state,args[3]);}
            case "resume"->{integrity(state);System.out.println("Resume from original "+state.start+", last verified "+state.lastCommit+". Any working changes remain unaccepted; run check again.");}
            case "report"->report(state);
            case "finish"->finish(state);
            default->throw new IllegalArgumentException("Unknown pass action: "+args[0]);
        }
    }
    static void start()throws Exception{
        if(Files.exists(local))throw new IllegalStateException("Existing run records at "+local+". Resume this run, or restart from its recorded original start in a fresh worktree; do not redefine changed HEAD as inherited debt.");
        if(!git("status","--porcelain").isBlank())throw new IllegalStateException("Commit or set aside unrelated edits before starting an isolated pass");
        String start=git("rev-parse","HEAD").trim();String id=Instant.now().toString().replaceAll("[^0-9]","");String branch="guardrails/pass-"+id;
        Path worktree=target.getParent().resolve(target.getFileName()+"-guardrails-"+id);
        git("worktree","add","-b",branch,worktree.toString(),start);target=worktree;local=target.resolve(DIRECTORY);Files.createDirectories(local);
        Path exclude=Path.of(git("rev-parse","--git-path","info/exclude").trim());if(!exclude.isAbsolute())exclude=target.resolve(exclude);
        Files.createDirectories(exclude.getParent());String old=Files.exists(exclude)?Files.readString(exclude):"";if(!old.contains("/"+DIRECTORY+"/"))Files.writeString(exclude,old+"\n/"+DIRECTORY+"/\n");
        State state=new State();state.version=version();state.start=start;state.lastCommit=start;state.branch=branch;
        resolveMode();state.required.addAll(DEFAULTS);state.required.addAll(optional());state.handsOff=handsOff();save(state);
        System.out.println("Work on "+target+"\nBranch: "+branch+"\nOriginal start: "+start+"\nRun baseline here before changing production code. Only decisions and the final report belong in commits; run records are locally ignored.");
    }
    static void resolveMode()throws Exception{
        var d=Util.Decisions.read(target);String mode=d.has("GUARDRAILS_MODE")?d.get("GUARDRAILS_MODE"):"unattended";
        if(!List.of("unattended","control").contains(mode))throw new IllegalArgumentException("GUARDRAILS_MODE must be unattended or control");
        recordDefault("GUARDRAILS_MODE",mode,"0.4.0 default; control preserves policy checkpoints");
        if(mode.equals("unattended")){
            var support=Util.modules(target).stream().filter(m->!m.testSupportReasons().isEmpty()).toList();
            if(d.has("TEST_SUPPORT_MODULES")&&!support.isEmpty()&&!d.testSupportDecided(support)){
                if(!d.get("TEST_SUPPORT_MODULES").startsWith("exclude "))throw new IllegalStateException("Unrecognized test-support decision; preserve and resolve it before proceeding");
                replaceDecision("EXCLUDED_MODULES",String.join(", ",d.excludedModules()),"preserve legacy general exclusions independently of test-support scope");
                replaceDecision("TEST_SUPPORT_MODULES","gate","previous entry excluded unrelated projects only; default to gating uncertain/published test support");
            }else recordDefault("TEST_SUPPORT_MODULES","gate","publication uncertain or published: own-module tests required; explicit exclusions preserved");
        }
    }
    static void recordDefault(String key,String value,String reason)throws Exception{
        var d=Util.Decisions.read(target);if(d.has(key))return;Path file=Util.Decisions.path(target);Files.createDirectories(file.getParent());
        String old=Files.exists(file)?Files.readString(file):"# java-guardrails decisions\n";
        Path tmp=Files.createTempFile(file.getParent(),"decisions-",".tmp");Files.writeString(tmp,old+"\n<!-- kit default: "+reason+" -->\n"+key+": "+value+"\n");Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    static void replaceDecision(String key,String value,String reason)throws Exception{
        Path file=Util.Decisions.path(target);String old=Files.exists(file)?Files.readString(file):"# java-guardrails decisions\n";
        String pattern="(?m)^\\s*(?:-\\s*)?"+java.util.regex.Pattern.quote(key)+":.*$";
        String updated=old.replaceAll(pattern,"<!-- previous "+key+" representation migrated; semantics preserved -->");
        Path tmp=Files.createTempFile(file.getParent(),"decisions-",".tmp");Files.writeString(tmp,updated+"\n<!-- kit default: "+reason+" -->\n"+key+": "+value+"\n");Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    static List<String> optional()throws Exception{return OptionalSnapshot.selected(target);}
    static void baseline(State state)throws Exception{
        if(state.baseline!=null)throw new IllegalStateException("Original baseline already recorded; use resume/check");
        if(!git("rev-parse","HEAD").trim().equals(state.start))throw new IllegalStateException("Baseline must start at the recorded original commit");
        resolveMode();var modules=Util.modules(target);if(Util.testSupportStop(modules,Util.Decisions.read(target)))return;
        for(String name:git("diff","--name-only",state.start).split("\n"))if(!name.isBlank()&&!name.equals("config/guardrails/decisions.md"))throw new IllegalStateException("Production changed before baseline: "+name);
        // Original source is measured in a detached worktree, so working-tree edits cannot redefine inherited debt.
        Path original=local.resolve("original");
        if(Files.exists(original)){
            if(!command(List.of("git","rev-parse","HEAD"),original,Map.of()).trim().equals(state.start))throw new IllegalStateException("Original worktree no longer matches the recorded start");
            for(String line:command(List.of("git","status","--porcelain","--untracked-files=all"),original,Map.of()).lines().toList())
                if(!line.substring(3).equals("config/guardrails/decisions.md"))throw new IllegalStateException("Original worktree changed; preserve it and recreate from "+state.start+": "+line);
        }else git("worktree","add","--detach",original.toString(),state.start);
        Path decisions=Util.Decisions.path(original);Files.createDirectories(decisions.getParent());Files.copy(Util.Decisions.path(target),decisions,StandardCopyOption.REPLACE_EXISTING);
        Path output=local.resolve("baseline");if(Files.exists(output))output=local.resolve("baseline-"+Instant.now().toString().replaceAll("[^0-9]",""));measure(original,output);
        state.required=new ArrayList<>(DEFAULTS);state.required.addAll(optional());requireSnapshots(state,output);
        state.baseline=output.toString();state.lastMeasurement=state.baseline;state.baselineHashes=hashes(output);state.lastHashes=new TreeMap<>(state.baselineHashes);state.handsOff=handsOff();save(state);
        System.out.println("Original findings and API captured. Plan the hotspot count and run estimate before editing; report progress during validation. Gate boundaries and finish require full measurement.");
    }
    static void integrity(State state)throws Exception{
        if(state.schema!=1||state.baseline==null)throw new IllegalStateException("Baseline not recorded");
        if(!Files.isRegularFile(Path.of(state.baseline,"consumer/dependencies.json")))throw new IllegalStateException("Original consumer closure is missing. Preserve this development run and restart from original "+state.start+"; do not redefine HEAD as the baseline.");
        if(!state.baselineHashes.equals(hashes(Path.of(state.baseline))))throw new IllegalStateException("Original evidence changed or disappeared; recreate the run from "+state.start+", never from HEAD");
        if(!state.lastHashes.equals(hashes(Path.of(state.lastMeasurement))))throw new IllegalStateException("Last accepted evidence changed; do not trust its counts");
        if(!git("rev-parse","HEAD").trim().equals(state.queued.isEmpty()?state.lastCommit:state.queued.getLast()))throw new IllegalStateException("HEAD is not the last accepted commit. Preserve the unverified changes, then return to "+state.lastCommit+" before check");
        if(!state.handsOff.equals(handsOff()))throw new IllegalStateException("HANDS_OFF scope/content changed; repair or undo the candidate");
        List<String> required=new ArrayList<>(DEFAULTS);required.addAll(optional());if(!required.equals(state.required))throw new IllegalStateException("Optional measurement contract changed; measure it at the original start before accepting work");
    }
    static void check(State state,boolean boundary,boolean finish)throws Exception{check(state,boundary,finish,false);}
    static void check(State state,boolean boundary,boolean finish,boolean sample)throws Exception{
        if(finish&&(!state.queued.isEmpty()||!git("status","--porcelain").isBlank()))throw new IllegalStateException("Final validation requires the accepted clean tree; validate and accept pending changes first");
        integrity(state);long start=System.nanoTime();String before=tree();Path output=local.resolve("checks/"+Instant.now().toString().replaceAll("[^0-9]",""));Files.createDirectories(output);
        state.pendingTree=null;state.pendingMeasurement=null;state.pendingHashes.clear();state.finalTree=null;save(state);
        var plan=ValidationPlan.create(target,state.lastCommit,before,Path.of(state.lastMeasurement),boundary,finish);
        if(!optional().isEmpty()&&!plan.full())plan=new ValidationPlan.Plan(plan.baseTree(),plan.candidateTree(),plan.changedFiles(),true,"configured optional checks require full measurement",Util.modules(target).stream().map(Util.Module::path).toList(),plan.resolveConsumer(),plan.previous());
        ValidationPlan.write(output.resolve("plan.json"),plan);
        System.out.println("Validation started: "+(plan.full()?"full":"scoped")+"; "+plan.modules().size()+" modules; "+plan.reason());
        try{
            evaluate(state,target,output,plan,plan.full());
            Files.writeString(output.resolve("candidate-validation-millis.txt"),Long.toString((System.nanoTime()-start)/1_000_000));
            if(!plan.full()&&sample){
                long proofStart=System.nanoTime();
                System.out.println("Scope proof: comparing this hotspot against a full measurement");
                Path full=output.resolve("full-equivalence");Files.createDirectories(full);
                var fullPlan=new ValidationPlan.Plan(plan.baseTree(),plan.candidateTree(),plan.changedFiles(),true,"scope equivalence sample",Util.modules(target).stream().map(Util.Module::path).toList(),false,plan.previous());
                measure(target,full,fullPlan);ValidationPlan.equivalent(output,full,state.required);
                Files.writeString(output.resolve("scope-equivalence.txt"),"Identical finding multisets for "+String.join(", ",state.required)+"\n");
                Files.writeString(output.resolve("equivalence-proof-millis.txt"),Long.toString((System.nanoTime()-proofStart)/1_000_000));state.scopeSamples++;
            }
        }catch(Exception failure){
            Files.writeString(output.resolve("failure.txt"),failure.toString()+"\n");state.redTree=before;save(state);
            if(state.queued.size()>1){System.out.println("Batch red; locating a failing prefix without accepting any commits");bisect(state);}
            throw failure;
        }
        if(!before.equals(tree()))throw new IllegalStateException("Candidate changed during validation; inspect and check again");
        long elapsed=(System.nanoTime()-start)/1_000_000;Files.writeString(output.resolve("hotspot-elapsed-millis.txt"),Long.toString(elapsed));
        if(finish){
            if(!state.queued.isEmpty())throw new IllegalStateException("Accept the checked batch before final validation");
            state.lastMeasurement=output.toString();state.lastHashes=hashes(output);state.finalTree=before;
        }else {state.pendingTree=before;state.pendingMeasurement=output.toString();state.pendingHashes=hashes(output);}
        save(state);if(state.plannedHotspots>0)System.out.println("Progress: "+state.steps.size()+"/"+state.plannedHotspots+" accepted hotspot commits; this candidate awaits acceptance.");System.out.printf(Locale.ROOT,"Candidate verified: API unchanged, consumer dependency changes absent or reasoned, no introduced findings. Validation %.1f minutes; %d accepted hotspot commits.%n",elapsed/60000.0,state.steps.size());
    }
    static void evaluate(State state,Path source,Path output,ValidationPlan.Plan plan,boolean enforce)throws Exception{
        measure(source,output,plan);requireSnapshots(state,output);List<String> introduced=new ArrayList<>();
        for(String gate:state.required){var added=Findings.introduced(Findings.read(Path.of(state.baseline,gate+".json")),Findings.read(Path.of(state.lastMeasurement,gate+".json")),Findings.read(output.resolve(gate+".json")));for(var f:added)introduced.add(gate+": "+f.rule()+" "+f.module()+" "+f.declaration());}
        if(!introduced.isEmpty()){Files.write(output.resolve("introduced.txt"),introduced);throw new IllegalStateException("Repair or undo introduced findings before any policy decision:\n"+String.join("\n",introduced));}
        script("ApiInventoryCheck.java",output.resolve("api-check.log"),Path.of(state.baseline,"api").toString(),output.resolve("api").toString(),kit.toString(),output.resolve("api-comparison").toString());
        script("ConsumerSnapshot.java",output.resolve("consumer-check.log"),"compare",Path.of(state.baseline,"consumer/dependencies.json").toString(),output.resolve("consumer/dependencies.json").toString(),Util.Decisions.path(source).toString(),output.resolve("consumer-changes.json").toString());
        if(enforce){
            List<String> verificationArgs=new ArrayList<>(List.of(source.toString(),kit.toString(),local.resolve("m2").toString(),output.resolve("verification").toString()));verificationArgs.addAll(installed());
            System.out.println("Gate boundary: verify original enforcement and plant installed gates");
            script("VerifyPass.java",output.resolve("verification.log"),verificationArgs.toArray(String[]::new));
        }
    }
    static void queue(State state,String message)throws Exception{
        integrity(state);var plan=ValidationPlan.create(target,state.lastCommit,tree(),Path.of(state.lastMeasurement),false,false);
        // Keep gates, configuration changes and cross-module batches out of this optimization.
        Set<String> owners=new TreeSet<>();for(String file:plan.changedFiles())for(var module:Util.modules(target))if(file.startsWith((module.path().equals(".")?"":module.path()+"/")+"src/"))owners.add(module.path());
        if(plan.full()||owners.size()!=1)throw new IllegalStateException("Batch hotspots must stay in one module between gate boundaries");
        git("add","-A");git("commit","-m",message);state.queued.add(git("rev-parse","HEAD").trim());state.pendingTree=null;state.pendingMeasurement=null;state.pendingHashes.clear();state.finalTree=null;save(state);
        System.out.println("Queued hotspot "+state.queued.getLast()+" (unverified). Validate the batch before accepting.");
    }
    static void acceptBatch(State state)throws Exception{
        integrity(state);if(state.queued.isEmpty())throw new IllegalStateException("No queued hotspot commits");
        if(!git("status","--porcelain").isBlank()||state.pendingTree==null||!state.pendingTree.equals(tree()))throw new IllegalStateException("Check the exact committed batch first");
        if(!state.pendingHashes.equals(hashes(Path.of(state.pendingMeasurement))))throw new IllegalStateException("Checked evidence changed");
        long elapsed=Long.parseLong(Files.readString(Path.of(state.pendingMeasurement,"hotspot-elapsed-millis.txt")).trim());
        for(String commit:state.queued)state.steps.add(new Step(commit,git("rev-parse",commit+"^{tree}").trim(),state.pendingMeasurement,commit.equals(state.queued.getLast())?elapsed:0));
        state.lastCommit=state.queued.getLast();state.queued.clear();state.lastMeasurement=state.pendingMeasurement;state.lastHashes=new TreeMap<>(state.pendingHashes);state.pendingTree=null;state.pendingMeasurement=null;state.pendingHashes.clear();save(state);System.out.println("Accepted batch; each hotspot retains its own undo commit");
    }
    static void bisect(State state)throws Exception{
        integrity(state);if(state.queued.isEmpty())throw new IllegalStateException("No batch to bisect");if(!tree().equals(state.redTree))throw new IllegalStateException("Check this exact batch and establish red before bisection");
        int good=-1,bad=state.queued.size()-1;Path evidence=local.resolve("bisect-"+Instant.now().toEpochMilli());Files.createDirectories(evidence);
        // The current tip is red. Test prefixes against the same original and last accepted
        // findings, never against another unaccepted prefix. Original branch is untouched.
        while(bad-good>1){int mid=(good+bad)/2;String commit=state.queued.get(mid);Path work=evidence.resolve("prefix-"+mid),out=evidence.resolve("result-"+mid);Files.createDirectories(out);
            git("worktree","add","--detach",work.toString(),commit);
            var plan=ValidationPlan.create(work,state.lastCommit,commit,Path.of(state.lastMeasurement),false,false);
            try{evaluate(state,work,out,plan,false);good=mid;Files.writeString(out.resolve("result.txt"),"green\n");}
            catch(Exception e){bad=mid;Files.writeString(out.resolve("result.txt"),"red: "+e+"\n");}
        }
        String result="Known failing hotspot: "+state.queued.get(bad)+"\nPrevious tested green prefix: "+(good<0?state.lastCommit:state.queued.get(good))+"\nNo batch commits accepted. Repair or undo, then validate the entire batch again.\n";
        Files.writeString(evidence.resolve("result.txt"),result);System.out.println(result);
    }
    static void estimate(State state,int hotspots)throws Exception{
        if(hotspots<1)throw new IllegalArgumentException("Hotspot count must be positive");
        state.plannedHotspots=hotspots;save(state);
        Path timing=Path.of(state.lastMeasurement,"hotspot-elapsed-millis.txt");if(!Files.exists(timing))timing=Path.of(state.lastMeasurement,"elapsed-millis.txt");
        double minutes=Double.parseDouble(Files.readString(timing).trim())/60000.0;
        System.out.printf(Locale.ROOT,"Plan: %d hotspots; conservative unbatched validation estimate %.1f minutes at %.1f measured minutes per validation, plus editing and final checks. Target: under 60 minutes end to end for an agent-client-sized project. Batching/scoping savings are reported only after measurement.%n",hotspots,hotspots*minutes,minutes);
    }
    static List<String> installed()throws Exception{
        StringBuilder xml=new StringBuilder();for(String file:git("ls-files","--cached","--others","--exclude-standard").split("\n"))if(file.endsWith("pom.xml")&&Files.exists(target.resolve(file)))xml.append(Files.readString(target.resolve(file)));
        List<String> result=new ArrayList<>();String text=xml.toString();
        if(text.contains("jacoco-maven-plugin")&&text.contains("<goal>check</goal>"))result.add("jacoco");
        if(text.contains("<id>errorprone</id>"))result.add("errorprone");
        if(text.contains("spotbugs-maven-plugin")&&text.contains("<goal>check</goal>"))result.add("spotbugs");
        if(text.contains("maven-pmd-plugin")&&text.contains("<goal>check</goal>")){result.add("pmd");result.add("cpd");}
        if(git("ls-files","--cached","--others","--exclude-standard").contains("NoCyclesTest.java"))result.add("archunit");
        if(optional().contains("nullaway"))result.add("nullaway");return result;
    }
    static void accept(State state,String message)throws Exception{
        integrity(state);if(!state.queued.isEmpty())throw new IllegalStateException("Use accept-batch for queued commits");if(state.pendingTree==null||!state.pendingTree.equals(tree()))throw new IllegalStateException("Run check on the exact candidate before accepting");
        requireSnapshots(state,Path.of(state.pendingMeasurement));
        if(!state.pendingHashes.equals(hashes(Path.of(state.pendingMeasurement))))throw new IllegalStateException("Checked evidence changed; rerun check");
        git("add","-A");if(!git("write-tree").trim().equals(state.pendingTree))throw new IllegalStateException("Staged tree differs from verified candidate");
        git("commit","-m",message);String head=git("rev-parse","HEAD").trim();
        if(!git("rev-parse","HEAD^{tree}").trim().equals(state.pendingTree))throw new IllegalStateException("Commit hook changed candidate; commit is unverified, do not claim acceptance");
        state.steps.add(new Step(head,state.pendingTree,state.pendingMeasurement,Long.parseLong(Files.readString(Path.of(state.pendingMeasurement,"hotspot-elapsed-millis.txt")).trim())));
        state.lastCommit=head;state.lastMeasurement=state.pendingMeasurement;state.lastHashes=new TreeMap<>(state.pendingHashes);state.pendingTree=null;state.pendingMeasurement=null;state.pendingHashes.clear();save(state);System.out.println("Accepted "+head);
    }
    static void report(State state)throws Exception{
        integrity(state);if(!git("status","--porcelain").isBlank())throw new IllegalStateException("Finish requires the accepted tree; pending changes cannot appear as complete");
        if(!state.queued.isEmpty())throw new IllegalStateException("Unaccepted batch cannot appear in the report");
        if(!tree().equals(state.finalTree))check(state,true,true);
        List<String> enforced=installed();boolean complete=enforced.containsAll(DEFAULTS)&&!state.steps.isEmpty();StringBuilder report=new StringBuilder();
        report.append(complete?"# Quality gates ready for your review\n":"# Quality pass incomplete — review the remaining scope\n");
        report.append("\nReview the local branch `").append(state.branch).append("` before merging. Run `./mvnw clean verify` from this checkout. Nothing was pushed.\n\n");
        report.append("Original start: `").append(state.start).append("`. Last verified commit: `").append(state.lastCommit).append("`. Kit: ").append(state.version).append(". Mode: ").append(Util.Decisions.read(target).get("GUARDRAILS_MODE")).append(".\n\n");
        report.append("Changes to review:\n\n");
        for(String commit:git("log","--format=%s",state.start+".."+state.lastCommit).lines().toList())report.append("- ").append(commit).append('\n');
        report.append("\nMeasured modules: ").append(String.join(", ",Findings.read(Path.of(state.lastMeasurement,"pmd.json")).modules())).append(".\n");
        var decisions=Util.Decisions.read(target);
        if(!decisions.excludedModules().isEmpty())report.append("Explicitly excluded from quality checks: ").append(String.join(", ",decisions.excludedModules())).append(". API protection still applies.\n");
        if(decisions.has("HANDS_OFF"))report.append("Reserved work: ").append(decisions.get("HANDS_OFF")).append(". Still measured.\n");
        if(decisions.has("ARCHUNIT_CYCLES"))report.append("Cycle enforcement scope/decision: ").append(decisions.get("ARCHUNIT_CYCLES")).append(".\n");
        report.append("\n| Check | Fixed or removed | Remaining measured findings | Introduced | Enforcement |\n|---|---:|---:|---:|---|\n");
        for(String gate:state.required){var initial=Findings.read(Path.of(state.baseline,gate+".json"));var now=Findings.read(Path.of(state.lastMeasurement,gate+".json"));int added=Findings.introduced(initial,now).size();if(added!=0)throw new IllegalStateException("Cannot finish with introduced findings");report.append("| ").append(checkName(gate)).append(" | ").append(initial.findings().size()-now.findings().size()).append(" | ").append(now.findings().size()).append(" | ").append(added).append(" | ").append(enforced.contains(gate)?"installed; representative plant verified":OPTIONAL.contains(gate)?"configured; assessed":"not installed").append(" |\n");}
        for(String gate:OPTIONAL)if(!state.required.contains(gate))report.append("| ").append(checkName(gate)).append(" | not assessed | not assessed | not assessed | not configured/selected |\n");
        report.append("\nPublished/protected API: ").append(state.steps.isEmpty()?"not yet checked against an accepted change":"unchanged against original artifacts at every accepted candidate or batch").append(". Findings introduced by this pass: 0 for every assessed check. Coverage counts are uncovered instructions, so their reduction means more code executed or unused code removed; SpotBugs includes all measured ranks. Finding counts include inherited exceptions and hands-off code; a nonzero remainder is not a claim that every finding is enforced. See `config/guardrails/decisions.md` for exact exclusions, inherited allowances and reasons.\n\n");
        Path dependencyReport=local.resolve("report-consumer-changes.json");
        if(ConsumerSnapshot.compare(Path.of(state.baseline,"consumer/dependencies.json"),Path.of(state.lastMeasurement,"consumer/dependencies.json"),Util.Decisions.path(target),dependencyReport)!=0)throw new IllegalStateException("Unrecorded consumer dependency changes prevent completion");
        var changes=ConsumerSnapshot.JSON.fromJson(Files.readString(dependencyReport),ConsumerSnapshot.Change[].class);
        report.append("Consumer dependencies: ").append(changes.length==0?"unchanged from the original start":"explicit changes recorded below").append(". Each publication was resolved from its publication POM in a separate consumer with no parent or imported BOM.\n\n");
        for(var change:changes)report.append("- `").append(change.publication()).append("`: `").append(change.dependency()).append("` ").append(change.before()).append(" → ").append(change.after()).append(". Reason: ").append(change.reason()).append("\n");
        report.append("**Next:** review the diff and decisions, then decide which commits to keep. Inherited findings above are follow-up work; use their saved per-gate details to choose a hotspot. A missing gate means this pass is incomplete.\n\n");
        report.append("Review: `git diff ").append(state.start).append("..").append(state.lastCommit).append("` and `git log --oneline ").append(state.start).append("..").append(state.lastCommit).append("`. Keep your original branch. To undo the whole unmerged pass, return to that branch; to revert individual commits, work in reverse order and rerun verification because later changes may depend on earlier ones.\n\n");
        report.append("| Accepted commit | Validation wall time |\n|---|---:|\n");for(Step step:state.steps)report.append("| `").append(step.commit).append("` | ").append(step.elapsedMillis/1000.0).append(" s |\n");
        double totalMinutes=(System.currentTimeMillis()-state.startedMillis)/60000.0;
        report.append("\nElapsed wall time since run start: ").append(totalMinutes).append(" minutes (includes editing and interruptions). Target: under 60 minutes for an agent-client-sized pass — ").append(totalMinutes<60?"met":"not met").append(". A zero time in the commit table shares the batch validation shown on its final commit. Scoped/full equivalence samples: ").append(state.scopeSamples).append(". Gate boundaries and finish were fully measured.\n");
        Path file=target.resolve("config/guardrails/report.md");Files.writeString(file,report);System.out.println(file+"\nThe report is ready to review and commit; it does not change the measured source tree.");
    }
    static String checkName(String gate){return switch(gate){
        case "jacoco"->"Test coverage (JaCoCo)";case "errorprone"->"Compiler checks (Error Prone)";
        case "spotbugs"->"Bug patterns (SpotBugs)";case "pmd"->"Complexity and duplication (PMD/CPD)";
        case "archunit"->"Dependency cycles (ArchUnit)";case "nullaway"->"Null contracts (NullAway)";
        case "pit"->"Test assertions (PIT)";case "lincheck"->"Concurrent behavior (Lincheck)";default->gate;};}
    static void finish(State state)throws Exception{
        integrity(state);if(!git("rev-parse","HEAD^{tree}").trim().equals(state.finalTree))throw new IllegalStateException("Run report/check-final for mandatory full final validation first");Path report=target.resolve("config/guardrails/report.md");if(!Files.isRegularFile(report))throw new IllegalStateException("Generate and review the report first");
        for(String line:git("status","--porcelain").lines().toList())if(!line.substring(3).equals("config/guardrails/report.md"))throw new IllegalStateException("Only the final report may change after the last verified hotspot: "+line);
        git("add","--","config/guardrails/report.md");git("commit","-m","Document quality gate scope and review instructions");
        if(!git("diff-tree","--no-commit-id","--name-only","-r","HEAD").trim().equals("config/guardrails/report.md"))throw new IllegalStateException("Commit hook changed more than the report; commit is unverified");
        state.lastCommit=git("rev-parse","HEAD").trim();save(state);System.out.println("Final report committed on "+state.branch+". Nothing pushed; review before merging.");
    }
    static Map<String,String> handsOff()throws Exception{
        Map<String,String> result=new TreeMap<>();String specification=Util.Decisions.read(target).get("HANDS_OFF");if(specification.isBlank())return result;
        List<Path> sources=new ArrayList<>();for(var module:Util.modules(target))sources.addAll(Util.javaFiles(module.sourceRoot()));
        for(String part:specification.split(";")){
            String[] fields=part.trim().split("\\s+\\|\\s+",2);if(fields.length!=2||fields[1].isBlank())throw new IllegalArgumentException("HANDS_OFF entries need selector | reason");String selector=fields[0];int matched=0;
            for(Path file:sources){String relative=target.relativize(file).toString().replace('\\','/');
                boolean whole=selector.equals("file:"+relative)||(selector.startsWith("module:")&&(relative.startsWith(selector.substring(7)+"/src/main/java/")||selector.equals("module:.")&&relative.startsWith("src/main/java/")));
                if(whole){result.put(selector+"|"+relative,Findings.fingerprint(target,List.of(file)));matched++;}
                else if(selector.startsWith("class:")||selector.startsWith("member:")){
                    String name=selector.substring(selector.indexOf(':')+1);for(var declaration:new Declarations(file).declarations)if(declaration.name().equals(name)){result.put(selector+"|"+relative,declaration.text());matched++;}
                }
            }
            if(matched==0||((selector.startsWith("class:")||selector.startsWith("member:"))&&matched!=1))throw new IllegalArgumentException("HANDS_OFF selector unmatched/ambiguous: "+selector);
        }
        return result;
    }
    static void requireSnapshots(State state,Path output)throws Exception{for(String gate:state.required){Path file=output.resolve(gate+".json");if(!Files.isRegularFile(file))throw new IllegalStateException("Required "+gate+" measurement missing; never report not assessed for a configured/selected gate");Findings.read(file);}ConsumerSnapshot.read(output.resolve("consumer/dependencies.json"));}
    static void measure(Path source,Path out)throws Exception{measure(source,out,null);}
    static void measure(Path source,Path out,ValidationPlan.Plan plan)throws Exception{
        Files.createDirectories(out);List<String> args=new ArrayList<>(List.of(source.toString(),out.toString(),kit.toString(),local.resolve("m2").toString()));
        if(plan!=null){Path file=out.resolve("plan.json");ValidationPlan.write(file,plan);args.add(file.toString());}
        script("MeasurePass.java",out.resolve("measurement.log"),args.toArray(String[]::new));
    }
    static Map<String,String> hashes(Path output)throws Exception{Map<String,String> hashes=new TreeMap<>();try(var paths=Files.walk(output)){for(Path file:paths.filter(Files::isRegularFile).toList())hashes.put(output.relativize(file).toString(),Findings.fingerprint(output,List.of(file)));}return hashes;}
    static String tree()throws Exception{
        // An alternate index hashes tracked and untracked candidate files without staging the user's index.
        Path index=local.resolve("candidate-index");Files.deleteIfExists(index);
        Map<String,String> env=Map.of("GIT_INDEX_FILE",index.toString());command(List.of("git","read-tree","HEAD"),target,env);command(List.of("git","add","-A"),target,env);return command(List.of("git","write-tree"),target,env).trim();
    }
    static String version()throws Exception{var match=java.util.regex.Pattern.compile("(?m)^  version: ['\"]?([^'\"\\s]+)").matcher(Files.readString(kit.resolve("SKILL.md")));if(!match.find())throw new IllegalStateException("Kit version missing");return match.group(1);}
    static State read()throws Exception{
        String json=Files.readString(local.resolve("state.json"));State state=new GsonBuilder().create().fromJson(json,State.class);
        if(!json.contains("\"startedMillis\""))state.startedMillis=state.baseline==null?Files.getLastModifiedTime(local.resolve("state.json")).toMillis():Files.getLastModifiedTime(Path.of(state.baseline)).toMillis();
        return state;
    }
    static void save(State state)throws Exception{Path tmp=local.resolve("state.tmp");Files.writeString(tmp,new GsonBuilder().setPrettyPrinting().create().toJson(state)+"\n");Files.move(tmp,local.resolve("state.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    static String git(String...args)throws Exception{List<String> cmd=new ArrayList<>(List.of("git"));cmd.addAll(List.of(args));return command(cmd,target,Map.of());}
    static String command(List<String> command,Path cwd,Map<String,String> env)throws Exception{ProcessBuilder pb=new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);pb.environment().putAll(env);Process p=pb.start();String text=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);if(p.waitFor()!=0)throw new IllegalStateException(String.join(" ",command)+": "+text);return text;}
    static void script(String script,Path log,String...args)throws Exception{List<String> cmd=new ArrayList<>(List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/"+script).toString()));cmd.addAll(List.of(args));run(cmd,target,log);}
    static void run(List<String> cmd,Path cwd,Path log)throws Exception{
        try{Run.checked(cmd,cwd,log);}catch(Exception e){
            if(!(e instanceof Run.Failed failed&&failed.exit==1&&cmd.stream().anyMatch(s->s.endsWith("ApiInventoryCheck.java")||s.endsWith("ConsumerSnapshot.java")))){String defect=Run.defect(target,kit,log.getParent(),e);Files.writeString(log.getParent().resolve("defect.md"),defect);System.err.println(defect);}
            throw e;
        }
    }
}
