///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.13.2
//SOURCES Util.java Xml.java Run.java
import java.nio.file.*;
import java.util.*;
import org.w3c.dom.*;
import com.google.gson.*;

/** Maven's external-consumer view, using the POMs actually installed by the publication build. */
public class ConsumerSnapshot {
 static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
 record Module(String publication,SortedMap<String,String> dependencies){}
 record Snapshot(int schema,String profiles,boolean complete,List<Module> modules){}
 record Change(String publication,String dependency,String before,String after,String reason){}
 public static void main(String[] args)throws Exception {
  if(args.length==1&&args[0].equals("--help")){System.out.println("ConsumerSnapshot.java capture SCRATCH_TARGET OUTPUT PRIVATE_MAVEN_REPO | compare START_JSON CURRENT_JSON DECISIONS REPORT_JSON\nCapture runs package and local install in scratch only, never deploy. Consumers have no parent or BOM. Exit 1 means unrecorded dependency change; 2 means incomplete evidence.");return;}
  try {
   if(args.length==4&&args[0].equals("capture"))capture(Path.of(args[1]).toAbsolutePath(),Path.of(args[2]).toAbsolutePath(),Path.of(args[3]).toAbsolutePath());
   else if(args.length==5&&args[0].equals("compare"))System.exit(compare(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),Path.of(args[4])));
   else throw new IllegalArgumentException("Use --help");
  }catch(Exception e){System.err.println("Consumer dependency evidence incomplete: "+e.getMessage());System.exit(2);}
 }
 static String profiles(Path target)throws Exception {
  var decisions=Util.Decisions.read(target);
  if(decisions.has("PUBLICATION_PROFILES")){
   String value=decisions.get("PUBLICATION_PROFILES");if(value.equals("none"))return "";
   if(!value.matches("[A-Za-z0-9_.-]+(,[A-Za-z0-9_.-]+)*"))throw new IllegalArgumentException("PUBLICATION_PROFILES must be none or comma-separated profile IDs");return value;
  }
  Set<String> publication=new TreeSet<>();boolean release=false;
  {
   for(Path pom:reactorPoms(target)){
    var entries=Xml.read(pom).getElementsByTagName("profile");
    for(int i=0;i<entries.getLength();i++){
     Element profile=(Element)entries.item(i);String id=Xml.text(profile,"id");if(id.equals("release"))release=true;
     var plugins=profile.getElementsByTagName("plugin");for(int j=0;j<plugins.getLength();j++)if(List.of("flatten-maven-plugin","central-publishing-maven-plugin","nexus-staging-maven-plugin").contains(Xml.text((Element)plugins.item(j),"artifactId")))publication.add(id);
    }
   }
  }
  if(release&&publication.stream().allMatch(p->p.equals("release")))return "release";
  if(publication.size()==1)return publication.iterator().next();
  if(!publication.isEmpty())throw new IllegalStateException("Ambiguous publication profiles "+publication+"; record PUBLICATION_PROFILES from the project's release command before continuing");
  return release?"release":"";
 }
 static List<Path> reactorPoms(Path target)throws Exception{
  Set<Path> seen=new LinkedHashSet<>();collectPoms(target.toRealPath(),target.toRealPath().resolve("pom.xml"),seen);return List.copyOf(seen);
 }
 static void collectPoms(Path root,Path pom,Set<Path> seen)throws Exception{
  pom=pom.toRealPath();if(!pom.startsWith(root))throw new IllegalStateException("Publication reactor extends outside scratch: "+pom);if(!seen.add(pom))return;
  Element project=Xml.read(pom).getDocumentElement();List<Element> lists=new ArrayList<>(Xml.children(project,"modules"));
  Element profiles=Xml.child(project,"profiles");if(profiles!=null)for(Element profile:Xml.children(profiles,"profile"))lists.addAll(Xml.children(profile,"modules"));
  for(Element list:lists)for(Element module:Xml.children(list,"module")){
   String path=module.getTextContent().trim();if(path.contains("${"))throw new IllegalStateException("Interpolated publication reactor requires explicit PUBLICATION_PROFILES before capture: "+path);
   Path child=pom.getParent().resolve(path).normalize();collectPoms(root,Files.isDirectory(child)?child.resolve("pom.xml"):child,seen);
  }
 }
 static void capture(Path target,Path output,Path repo)throws Exception {
  Files.createDirectories(repo);repo=repo.toRealPath();
  if(repo.startsWith(Path.of(System.getProperty("user.home"),".m2").toAbsolutePath()))throw new IllegalArgumentException("Never stage into ~/.m2; use private Maven storage");
  Files.createDirectories(output);String profile=profiles(target);Path effective=output.resolve("effective-pom.xml"),log=output.resolve("publication-build.log");
  var command=Util.maven(target,"archunit","");command.addAll(List.of("-Dmaven.repo.local="+repo,"-DskipTests","-Dgpg.skip=true"));
  if(!profile.isEmpty())command.addAll(List.of("-P",profile+",!errorprone"));
  // A direct install goal follows package: use MavenProject.file after flatten/other publication
  // transformations, without executing deploy, signing or release-upload lifecycle phases.
  command.addAll(List.of("org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom","-Doutput="+effective,"package","org.apache.maven.plugins:maven-install-plugin:3.1.4:install"));
  Run.checked(command,target,log);
  Element root=Xml.read(effective).getDocumentElement();List<Element> projects=root.getTagName().equals("project")?List.of(root):Xml.children(root,"project");
  if(projects.isEmpty())throw new IllegalStateException("No effective reactor models");
  String buildLog=Files.readString(log);List<Module> modules=new ArrayList<>();Set<String> publications=new HashSet<>();int index=0;
  for(Element project:projects){
   String group=Xml.text(project,"groupId"),artifact=Xml.text(project,"artifactId"),version=Xml.text(project,"version"),packaging=Xml.text(project,"packaging");if(packaging.isEmpty())packaging="jar";
   String gav=group+":"+artifact+":"+version;if(gav.contains("${")||group.isBlank()||artifact.isBlank()||version.isBlank())throw new IllegalStateException("Unresolved publication coordinates: "+gav);
   if(skipPublication(project))continue;
   if(!List.of("jar","pom").contains(packaging))throw new IllegalStateException("Unsupported consumer packaging "+packaging+" for "+gav+"; do not omit this publication");
   Path versionDir=repo.resolve(group.replace('.','/')).resolve(artifact).resolve(version);Path pom=versionDir.resolve(artifact+"-"+version+".pom");
   if(!installed(buildLog,pom))throw new IllegalStateException("Publication POM was not installed by this build (stale cache is not evidence): "+pom);
   Path saved=output.resolve("poms").resolve(group.replace('.','/')).resolve(artifact).resolve(version).resolve(pom.getFileName());Files.createDirectories(saved.getParent());Files.copy(pom,saved);
   List<String> classifiers=new ArrayList<>();classifiers.add("");
   if(packaging.equals("jar")){
    Path jar=versionDir.resolve(artifact+"-"+version+".jar");if(!installed(buildLog,jar))throw new IllegalStateException("Missing current publication artifact: "+jar);
    try(var files=Files.list(versionDir)){for(Path attachment:files.filter(p->p.toString().endsWith(".jar")).sorted().toList()){
     String name=attachment.getFileName().toString(),prefix=artifact+"-"+version+"-";
     if(name.startsWith(prefix)&&installed(buildLog,attachment)){String classifier=name.substring(prefix.length(),name.length()-4);if(!List.of("sources","javadoc","test-sources").contains(classifier))classifiers.add(classifier);}
    }}
   }
   for(String classifier:classifiers){
    String publication=group+":"+artifact+":"+packaging+":"+classifier;
    if(!publications.add(publication))throw new IllegalStateException("Duplicate publication "+publication);
    Path consumer=output.resolve("consumers/"+index++);Files.createDirectories(consumer);copyWrapper(target,consumer);
    String dependency="<groupId>"+escape(group)+"</groupId><artifactId>"+escape(artifact)+"</artifactId><version>"+escape(version)+"</version><type>"+packaging+"</type>"+(classifier.isEmpty()?"":"<classifier>"+escape(classifier)+"</classifier>");
    Files.writeString(consumer.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>guardrails.consumer</groupId><artifactId>probe</artifactId><version>1</version><dependencies><dependency>"+dependency+"</dependency></dependencies></project>");
    Files.writeString(consumer.resolve("settings.xml"),"<settings/>");Path tree=consumer.resolve("tree.json");
    Run.checked(List.of(Util.mvnw(consumer),"-B","-Dstyle.color=never","-s",consumer.resolve("settings.xml").toString(),"-gs",consumer.resolve("settings.xml").toString(),"-Dmaven.repo.local="+repo,"org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree","-DoutputType=json","-DoutputFile="+tree),consumer,consumer.resolve("resolve.log"));
    String resolution=Files.readString(consumer.resolve("resolve.log"));
    boolean missingMetadata=resolution.lines().map(s->s.toLowerCase(Locale.ROOT)).anyMatch(s->s.contains("[warning]")&&(s.contains("pom")||s.contains("descriptor"))&&(s.contains("missing")||s.contains("invalid")||s.contains("could not")||s.contains("failed")));
    if(missingMetadata){System.err.print(resolution);throw new IllegalStateException("Consumer dependency metadata incomplete for "+publication+"; Maven omitted transitive information despite exit 0. See "+consumer.resolve("resolve.log"));}
    JsonObject graph=JsonParser.parseString(Files.readString(tree)).getAsJsonObject();JsonArray children=graph.getAsJsonArray("children");
    if(children==null||children.size()!=1)throw new IllegalStateException("Consumer did not resolve exactly one requested publication: "+publication);
    JsonObject direct=children.get(0).getAsJsonObject();if(!coordinate(direct).equals(publication)||!string(direct,"version").equals(version))throw new IllegalStateException("Consumer resolved a different publication: "+publication);
    SortedMap<String,String> dependencies=new TreeMap<>();walk(direct,dependencies);modules.add(new Module(publication,dependencies));
   }
  }
  if(modules.isEmpty())throw new IllegalStateException("No publications resolved; establish publication scope explicitly");
  modules.sort(Comparator.comparing(Module::publication));Files.writeString(output.resolve("dependencies.json"),JSON.toJson(new Snapshot(1,profile,true,modules))+"\n");
  System.out.println("Consumer dependency closure captured for "+modules.size()+" publications (profiles: "+(profile.isEmpty()?"default":profile)+")");
 }
 static boolean installed(String log,Path destination){return log.lines().anyMatch(s->s.contains("Installing ")&&s.endsWith(" to "+destination));}
 static boolean skipPublication(Element project){
  Element build=Xml.child(project,"build"),plugins=build==null?null:Xml.child(build,"plugins");if(plugins==null)return false;
  for(Element plugin:Xml.children(plugins,"plugin")){
   Element config=Xml.child(plugin,"configuration");if(config==null)continue;String id=Xml.text(plugin,"artifactId");
   if(id.equals("central-publishing-maven-plugin")&&Xml.text(config,"skipPublishing").equals("true"))return true;
   if(id.equals("maven-deploy-plugin")&&Xml.text(config,"skip").equals("true"))return true;
  }return false;
 }
 static void copyWrapper(Path from,Path to)throws Exception{
  for(String name:List.of("mvnw","mvnw.cmd"))if(Files.exists(from.resolve(name))){Files.copy(from.resolve(name),to.resolve(name));to.resolve(name).toFile().setExecutable(true);}
  Path wrapper=from.resolve(".mvn/wrapper");if(!Files.isDirectory(wrapper))throw new IllegalStateException("Maven wrapper metadata missing");
  try(var files=Files.walk(wrapper)){for(Path file:files.filter(Files::isRegularFile).toList()){Path dest=to.resolve(".mvn/wrapper").resolve(wrapper.relativize(file));Files.createDirectories(dest.getParent());Files.copy(file,dest);}}
 }
 static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;");}
 static String string(JsonObject o,String key){return o.has(key)?o.get(key).getAsString():"";}
 static String coordinate(JsonObject o){return string(o,"groupId")+":"+string(o,"artifactId")+":"+string(o,"type")+":"+string(o,"classifier");}
 static void walk(JsonObject node,SortedMap<String,String> dependencies){
  String key=coordinate(node),value=string(node,"version")+"@"+string(node,"scope");
  String old=dependencies.putIfAbsent(key,value);if(old!=null&&!old.equals(value))throw new IllegalStateException("Ambiguous resolved dependency "+key);
  if(node.has("children"))for(JsonElement child:node.getAsJsonArray("children"))walk(child.getAsJsonObject(),dependencies);
 }
 static Snapshot read(Path path)throws Exception{
  Snapshot snapshot=JSON.fromJson(Files.readString(path),Snapshot.class);
  if(snapshot==null||snapshot.schema()!=1||!snapshot.complete()||snapshot.profiles()==null||snapshot.modules()==null||snapshot.modules().isEmpty())throw new IllegalStateException("Incomplete consumer snapshot: "+path);
  Set<String> seen=new HashSet<>();for(Module module:snapshot.modules())if(module.publication()==null||!seen.add(module.publication())||module.dependencies()==null||!module.dependencies().containsKey(module.publication()))throw new IllegalStateException("Incomplete/duplicate publication: "+path);
  return snapshot;
 }
 static int compare(Path start,Path current,Path decisions,Path report)throws Exception{
  Snapshot old=read(start),now=read(current);
  if(!old.profiles().equals(now.profiles()))throw new IllegalStateException("Publication profiles changed; use the original publication contract");
  if(!old.modules().stream().map(Module::publication).toList().equals(now.modules().stream().map(Module::publication).toList())){System.err.println("Consumer dependency change: publication inventory changed");return 1;}
  Map<String,String> reasons=new HashMap<>();if(Files.isRegularFile(decisions))for(String line:Files.readAllLines(decisions))if(line.startsWith("DEPENDENCY_CHANGE:")){
   String[] parts=line.substring("DEPENDENCY_CHANGE:".length()).trim().split("\\s*\\|\\s*",5);
   if(parts.length!=5||Arrays.stream(parts).anyMatch(String::isBlank)||Arrays.stream(parts).anyMatch(p->p.contains("*")))throw new IllegalArgumentException("DEPENDENCY_CHANGE requires exact publication | dependency | before@scope | after@scope | reason");
   if(reasons.putIfAbsent(String.join("|",Arrays.copyOf(parts,4)),parts[4])!=null)throw new IllegalArgumentException("Duplicate DEPENDENCY_CHANGE");
  }
  List<Change> changes=new ArrayList<>();boolean rejected=false;
  for(int i=0;i<old.modules().size();i++){
   Module a=old.modules().get(i),b=now.modules().get(i);Set<String> keys=new TreeSet<>(a.dependencies().keySet());keys.addAll(b.dependencies().keySet());
   for(String key:keys){String before=a.dependencies().getOrDefault(key,"absent"),after=b.dependencies().getOrDefault(key,"absent");if(before.equals(after))continue;
    String reason=reasons.get(String.join("|",a.publication(),key,before,after));changes.add(new Change(a.publication(),key,before,after,reason));
    System.out.println(a.publication()+": "+key+" "+before+" -> "+after+(reason==null?" — UNRECORDED":" — "+reason));if(reason==null)rejected=true;
   }
  }
  Files.createDirectories(report.toAbsolutePath().getParent());Files.writeString(report,JSON.toJson(changes)+"\n");
  if(rejected){System.err.println("Consumer dependency change requires repair or an exact DEPENDENCY_CHANGE with a reason in decisions.md");return 1;}
  System.out.println(changes.isEmpty()?"Consumer dependency closures unchanged":"Consumer dependency changes recorded with reasons: "+changes.size());return 0;
 }
}
