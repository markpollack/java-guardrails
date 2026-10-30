///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES ReactorScopeTest.java ../scripts/Util.java
import java.nio.file.*;
import java.util.*;
public class PassPolicyTest extends ReactorScopeTest {
    public static void main(String[] args)throws Exception{
        kit=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();root=Files.createTempDirectory("guardrails-pass-policy-");
        for(String scenario:List.of("unattended","control","legacy-exclusion")){
            String mode=scenario.equals("control")?"control":"unattended";
            Path target=fixture(scenario,false,false);decisions(target,(mode.equals("control")?"GUARDRAILS_MODE: control\n":"")+"PROJECT_NOTE: preserve this\n"+(scenario.equals("legacy-exclusion")?"TEST_SUPPORT_MODULES: exclude tools/docgen\n":""));
            check(run(target,List.of("git","init","-q")),mode+" init");check(run(target,List.of("git","add","-A")),mode+" stage");check(run(target,List.of("git","-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","-qm","fixture")),mode+" commit");
            String original=Files.readString(Util.Decisions.path(target));Result result=run(target,List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/Pass.java").toString(),"start",target.toString(),kit.toString()));check(result,mode+" starts isolated local worktree","Work on ");
            Path worktree=Path.of(result.output().lines().filter(l->l.startsWith("Work on ")).findFirst().orElseThrow().substring(8));
            String decisions=Files.readString(Util.Decisions.path(worktree));
            require(decisions.contains("GUARDRAILS_MODE: "+mode)&&decisions.contains("PROJECT_NOTE: preserve this"),mode+" recorded without losing explicit decisions",decisions);
            require(decisions.contains("TEST_SUPPORT_MODULES: gate")==mode.equals("unattended"),mode+" scope policy",decisions);
            if(scenario.equals("legacy-exclusion"))require(decisions.contains("EXCLUDED_MODULES: tools/docgen"),"legacy general exclusion preserved independently of support default",decisions);
            require(Files.readString(Util.Decisions.path(target)).equals(original),mode+" original worktree unchanged","");
            Result status=run(worktree,List.of("git","status","--porcelain"));require(!status.output().contains(".guardrails-run"),mode+" run state ignored",status.output());
            Result recapture=run(worktree,List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/Pass.java").toString(),"start",worktree.toString(),kit.toString()));
            require(recapture.exit()!=0&&recapture.output().contains("recorded original start"),mode+" cannot recapture a run as a new baseline",recapture.output());
            if(mode.equals("control")){
                Result stop=run(worktree,List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/Pass.java").toString(),"baseline",worktree.toString(),kit.toString()));check(stop,"control asks test-support before measurements","STOP TEST_SUPPORT_MODULES (decide)");
                require(!Files.exists(worktree.resolve(".guardrails-run/baseline")),"control cannot measure unanswered scope","");
            }
            Path otherKit=root.resolve(scenario+"-new-kit");write(otherKit,"SKILL.md","metadata:\n  version: \"99.0.0\"\n");
            Result changed=run(worktree,List.of(kit.resolve("jbang").toString(),kit.resolve("scripts/Pass.java").toString(),"resume",worktree.toString(),otherKit.toString()));
            require(changed.exit()!=0&&changed.output().contains("Restart from original start"),mode+" kit change refuses resume from current HEAD",changed.output());
        }
        if(!failures.isEmpty())throw new AssertionError(failures);System.out.println("PASS pass mode/scope/resume policies; evidence "+root);
    }
}
