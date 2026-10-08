///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS net.sourceforge.pmd:pmd-java:7.28.0
//DEPS org.slf4j:slf4j-simple:1.7.36
//SOURCES Util.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.cpd.CPDConfiguration;
import net.sourceforge.pmd.cpd.CpdAnalysis;
import net.sourceforge.pmd.cpd.Mark;
import net.sourceforge.pmd.cpd.Match;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.java.JavaLanguageModule;
import net.sourceforge.pmd.lang.java.ast.ASTBlock;
import net.sourceforge.pmd.lang.java.ast.ASTConstructorDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTFieldDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTLambdaExpression;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTStatement;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.ast.ModifierOwner.Visibility;
import net.sourceforge.pmd.lang.java.metrics.JavaMetrics;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.metrics.MetricsUtil;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleContext;

/**
 * Measure a Java code base before gating it: compute, for every method, class and lambda in the
 * target's main sources, the metrics the PMD gate will later enforce, and print each metric's
 * distribution, its worst cases by name, and the cost at the kit's reference threshold with a fit
 * check. CPD runs at a low token count so the 100-token cut can be seen.
 *
 * Nothing here needs the target's build: PMD's metrics engine runs in-process over every
 * {@code src/main/java} below the target, so the same measurement serves Maven and Gradle.
 *
 * <p>The owner's decisions live in the target at {@code config/guardrails/decisions.md}, one
 * {@code KEY: value} line each. When a decision this measurement needs is missing, the report ends
 * with a STOP block for it: the data, the options, a recommendation and the line to record. The
 * agent relays the block verbatim, records the answer, and reruns; the script never re-asks.
 *
 * Usage: {@code ./jbang measure [target] [--top N] [--cpd-tokens N] [--exclude module,module]}
 */
public class PmdMeasure {

	/** One measured item: a method, class, lambda or duplicate block. {@code module} is the Maven or Gradle module path. */
	record Item(String name, String where, String module, int value) {
	}

	/**
	 * A metric to be gated. {@code reference} is the threshold the kit proved on its first code
	 * base; every rule here reports values at or above its threshold, so a threshold of T means
	 * "T fails".
	 */
	record Metric(String key, String title, String rule, int reference, List<Item> items) {
		long failing() {
			return items.stream().filter(i -> i.value() >= reference).count();
		}
	}

	/** A module below the target: its path relative to the target and why it looks like test support, if it does. */
	record Module(String path, Path sourceRoot, List<String> testSupportReasons) {
	}

	/** A type that fails class NCSS for a reason other than tangled code, with the facts that say so. */
	record ClassSizeCase(String simpleName, String binaryName, int ncss, String shape, String recommendation) {
	}

	static final List<Item> METHOD_COGNITIVE = sync();
	static final List<Item> METHOD_CYCLO = sync();
	static final List<Item> METHOD_NCSS = sync();
	static final List<Item> METHOD_PARAMS = sync();
	static final List<Item> CLASS_NCSS = sync();
	static final List<Item> CLASS_CYCLO = sync();
	static final List<Item> LAMBDA_STATEMENTS = sync();
	static final Set<String> WIRE_CONTAINERS = Collections.synchronizedSet(new HashSet<>());
	static final Map<String, ClassSizeCase> CLASS_SIZE_CASES = Collections.synchronizedMap(new TreeMap<>());

	/** Statement node types counted by the LongLambda rule in configs/pmd/ruleset.xml. */
	static final Set<String> COUNTED_STATEMENTS = Set.of("ASTLocalVariableDeclaration", "ASTExpressionStatement",
			"ASTReturnStatement", "ASTIfStatement", "ASTForStatement", "ASTForeachStatement", "ASTWhileStatement",
			"ASTDoStatement", "ASTTryStatement", "ASTThrowStatement", "ASTSwitchStatement", "ASTBreakStatement",
			"ASTContinueStatement", "ASTSynchronizedStatement", "ASTYieldStatement", "ASTLocalClassStatement");

	/** Test libraries whose presence without {@code <scope>test</scope>} marks a module as test support. */
	static final Pattern TEST_LIBRARY = Pattern.compile(
			"<artifactId>(junit[a-z0-9-]*|assertj[a-z0-9-]*|mockito[a-z0-9-]*|testcontainers[a-z0-9-]*|hamcrest[a-z0-9-]*|testng|spring-boot-starter-test|spring-test)</artifactId>");
	static final Pattern TEST_MODULE_NAME = Pattern.compile("(^|[-_/])(test|tests|testing|test-support|testkit|conformance)([-_/]|$)");

	static final int FIT_PERCENT = 5;
	static final int FIX_ALL_LIMIT = 100;
	static final int CLASS_NCSS_REFERENCE = 300;

	public static void main(String[] args) throws IOException {
		Path target = Util.target(args);
		int top = intOption(args, "--top", 10);
		int cpdTokens = intOption(args, "--cpd-tokens", 50);
		if (!Files.isDirectory(target)) {
			System.err.println("measure: not a directory: " + target);
			System.exit(2);
		}
		Decisions decisions = Decisions.read(target);
		List<Module> allModules = modules(target);
		Set<String> excluded = new HashSet<>(listOption(args, "--exclude"));
		excluded.addAll(decisions.excludedModules());
		List<Module> gated = allModules.stream().filter(m -> excluded.stream().noneMatch(x -> m.path().equals(x) || m.path().startsWith(x + "/"))).collect(Collectors.toList());
		if (gated.isEmpty()) {
			System.err.println("measure: no src/main/java below " + target + " outside the excluded modules");
			System.exit(2);
		}
		String javaVersion = Util.javaVersion(target);
		System.out.println("# Measurement: " + target.getFileName());
		System.out.println();
		System.out.println("Target `" + target + "`, Java " + javaVersion + ", PMD 7.28.0, main sources only.");
		System.out.println("Modules measured: " + gated.stream().map(Module::path).collect(Collectors.joining(", ")));
		if (!excluded.isEmpty()) {
			System.out.println("Modules left out: " + excluded.stream().sorted().collect(Collectors.joining(", ")));
		}
		System.out.println();
		decisions.print();

		MeasureRule.MODULES = gated;
		analyse(target, gated, javaVersion);

		List<Metric> metrics = List.of(
				new Metric("cognitive", "Cognitive complexity, method", "CognitiveComplexity reportLevel", 15, METHOD_COGNITIVE),
				new Metric("cyclo", "Cyclomatic complexity, method", "CyclomaticComplexity methodReportLevel", 10, METHOD_CYCLO),
				new Metric("class-cyclo", "Cyclomatic complexity, class total", "CyclomaticComplexity classReportLevel", 80, CLASS_CYCLO),
				new Metric("ncss", "NCSS, method", "NcssCount methodReportLevel", 30, METHOD_NCSS),
				new Metric("class-ncss", "NCSS, class (nested classes counted)", "NcssCount classReportLevel", CLASS_NCSS_REFERENCE, applyClassSizeExemptions(CLASS_NCSS, decisions)),
				new Metric("lambda", "Statements in one lambda body", "LongLambda (XPath)", 30, LAMBDA_STATEMENTS),
				new Metric("params", "Parameters, method or constructor (record canonical constructors exempt)", "ExcessiveParameterList minimum", 7, METHOD_PARAMS));
		List<Metric> unfit = new ArrayList<>();
		for (Metric m : metrics) {
			if (!report(m, top)) {
				unfit.add(m);
			}
		}
		if (!WIRE_CONTAINERS.isEmpty()) {
			System.out.println("Classes matching the wire-record-container exemption (class NCSS only): "
					+ WIRE_CONTAINERS.stream().sorted().collect(Collectors.joining(", ")));
			System.out.println();
		}
		List<Item> cpdBlocks = cpd(target, gated, javaVersion, cpdTokens, top);

		stops(target, allModules, gated, metrics, unfit, cpdBlocks, decisions);
	}

	// ---- PMD metrics, in-process ---------------------------------------------------------------

	static void analyse(Path target, List<Module> modules, String javaVersion) throws IOException {
		PMDConfiguration config = new PMDConfiguration();
		config.setDefaultLanguageVersion(JavaLanguageModule.getInstance().getVersion(javaVersion));
		config.setIgnoreIncrementalAnalysis(true);
		config.setThreads(1);
		config.setReportFormat("empty");
		config.setReporter(new TracingReporter());
		MeasureRule.TARGET = target;
		MeasureRule rule = new MeasureRule();
		try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
			pmd.addRuleSet(RuleSet.forSingleRule(rule));
			for (Module module : modules) {
				pmd.files().addDirectory(module.sourceRoot());
			}
			Report report = pmd.performAnalysisAndCollectReport();
			for (Report.ProcessingError error : report.getProcessingErrors()) {
				System.err.println("measure: " + error.getFileId().getOriginalPath() + ": " + error.getMsg());
				System.err.println(error.getDetail());
			}
			if (!report.getProcessingErrors().isEmpty()) {
				System.exit(1);
			}
		}
	}

	/** PMD's default reporter drops the stack trace of an analysis failure; this one prints it. */
	static final class TracingReporter implements net.sourceforge.pmd.util.log.PmdReporter {
		private int errors;

		@Override
		public boolean isLoggable(org.slf4j.event.Level level) {
			return level.toInt() >= org.slf4j.event.Level.WARN.toInt();
		}

		@Override
		public void logEx(org.slf4j.event.Level level, String message, Object[] args, Throwable error) {
			if (!isLoggable(level)) {
				return;
			}
			if (level == org.slf4j.event.Level.ERROR) {
				errors++;
			}
			System.err.println("pmd " + level + ": " + java.text.MessageFormat.format(message, args));
			if (error != null) {
				error.printStackTrace();
			}
			for (Object arg : args) {
				if (arg instanceof Throwable t) {
					t.printStackTrace();
				}
			}
		}

		@Override
		public int numErrors() {
			return errors;
		}
	}

	/** Visits every executable, type and lambda and records the metric values; reports nothing. */
	static final class MeasureRule extends AbstractJavaRule {
		/** Static because PMD copies rules per thread through the no-arg constructor. */
		static Path TARGET;
		static List<Module> MODULES;

		MeasureRule() {
			setName("Measure");
			setLanguage(JavaLanguageModule.getInstance());
			setMessage("measurement only");
			setDescription("Collects metric values; never reports a violation");
		}

		@Override
		public void apply(Node node, RuleContext ctx) {
			// crossFindBoundaries: without it PMD's node streams stop at nested types and lambdas,
			// which hid a third of the methods on the first run
			for (Node n : node.descendantsOrSelf().crossFindBoundaries(true)) {
				try {
					if (n instanceof ASTExecutableDeclaration e) {
						executable(e);
					}
					else if (n instanceof ASTTypeDeclaration t && !t.isAnonymous()) {
						type(t);
					}
					else if (n instanceof ASTLambdaExpression l && l.getBlockBody() != null) {
						lambda(l);
					}
				}
				catch (Throwable ex) {
					System.err.println("measure: " + n.getXPathNodeName() + " at line " + n.getBeginLine());
					ex.printStackTrace();
					throw ex;
				}
			}
		}

		private void executable(ASTExecutableDeclaration e) {
			String name = owner(e) + "." + e.getName() + "(" + e.getArity() + ")";
			String where = where(e);
			String module = module(e);
			// ExcessiveParameterList counts every parameter list, abstract or not, except a private
			// constructor's (PMD) and a record's canonical constructor (the ruleset's exemption)
			if (!(e instanceof ASTConstructorDeclaration c && (c.getVisibility() == Visibility.V_PRIVATE || isCanonicalRecordConstructor(c)))) {
				METHOD_PARAMS.add(new Item(name, where, module, e.getArity()));
			}
			if (e.getBody() == null) {
				return; // abstract or interface method: no complexity or size to measure
			}
			METHOD_COGNITIVE.add(new Item(name, where, module, MetricsUtil.computeMetric(JavaMetrics.COGNITIVE_COMPLEXITY, e)));
			METHOD_CYCLO.add(new Item(name, where, module, MetricsUtil.computeMetric(JavaMetrics.CYCLO, e)));
			METHOD_NCSS.add(new Item(name, where, module, MetricsUtil.computeMetric(JavaMetrics.NCSS, e)));
		}

		private static boolean isCanonicalRecordConstructor(ASTConstructorDeclaration c) {
			ASTTypeDeclaration owner = c.getEnclosingType();
			return owner != null && owner.isRecord() && owner.getRecordComponents() != null
					&& c.getArity() == owner.getRecordComponents().size();
		}

		private void type(ASTTypeDeclaration t) {
			String name = t.getBinaryName();
			String where = where(t);
			String module = module(t);
			if (JavaMetrics.NCSS.supports(t)) {
				int ncss = MetricsUtil.computeMetric(JavaMetrics.NCSS, t);
				CLASS_NCSS.add(new Item(name, where, module, ncss));
				if (ncss >= CLASS_NCSS_REFERENCE && !isWireRecordContainer(t)) {
					ClassSizeCase c = classSizeCase(t, ncss);
					if (c != null) {
						CLASS_SIZE_CASES.put(c.simpleName(), c);
					}
				}
			}
			if (JavaMetrics.WEIGHED_METHOD_COUNT.supports(t)) {
				// what CyclomaticComplexity's classReportLevel compares against
				CLASS_CYCLO.add(new Item(name, where, module, MetricsUtil.computeMetric(JavaMetrics.WEIGHED_METHOD_COUNT, t)));
			}
			if (isWireRecordContainer(t)) {
				WIRE_CONTAINERS.add(name);
			}
		}

		/**
		 * The shapes that fail class NCSS without being tangled code: a type whose statements are
		 * mostly nested records (a schema), or an interface whose size is nested builder classes
		 * (an API surface). Anything else is a class to split and gets no case.
		 */
		private static ClassSizeCase classSizeCase(ASTTypeDeclaration t, int ncss) {
			List<ASTTypeDeclaration> nested = t.getDeclarations(ASTTypeDeclaration.class).toList();
			long records = nested.stream().filter(ASTTypeDeclaration::isRecord).count();
			long classes = nested.stream().filter(ASTTypeDeclaration::isRegularClass).count();
			long instanceFields = t.getDeclarations(ASTFieldDeclaration.class).filter(f -> !f.isStatic()).count();
			long ownMethods = t.getDeclarations(ASTMethodDeclaration.class).count();
			String facts = String.format(Locale.ROOT, "%d statements; nested: %d records, %d classes; own: %d methods, %d instance fields",
					ncss, records, classes, ownMethods, instanceFields);
			if (t.isRegularClass() && records >= 10 && records > classes && instanceFields == 0) {
				return new ClassSizeCase(t.getSimpleName(), t.getBinaryName(), ncss,
						"wire schema: " + facts + "; misses the wire-record-container exemption only through its nested classes",
						"exempt (its size is the protocol's) or move the nested classes to their own files so the existing exemption matches");
			}
			if (t.isInterface() && classes >= 2) {
				return new ClassSizeCase(t.getSimpleName(), t.getBinaryName(), ncss,
						"interface carrying builders: " + facts,
						"refactor: move the nested classes to their own files; the interface then measures its own methods");
			}
			return null;
		}

		private void lambda(ASTLambdaExpression l) {
			ASTBlock body = l.getBlockBody();
			int count = (int) body.descendants(ASTStatement.class).crossFindBoundaries(true).toStream()
					.filter(s -> COUNTED_STATEMENTS.contains(s.getClass().getSimpleName())).count();
			ASTExecutableDeclaration enclosing = l.ancestors(ASTExecutableDeclaration.class).first();
			String name = (enclosing != null ? owner(enclosing) + "." + enclosing.getName() : owner(l)) + " lambda";
			LAMBDA_STATEMENTS.add(new Item(name, where(l), module(l), count));
		}

		/** The ruleset's violationSuppressXPath for class NCSS, as a predicate. */
		private static boolean isWireRecordContainer(ASTTypeDeclaration t) {
			if (!t.isRegularClass() || !t.isFinal()) {
				return false;
			}
			boolean hasRecord = t.getDeclarations(ASTTypeDeclaration.class).any(ASTTypeDeclaration::isRecord);
			boolean hasPlainNestedClass = t.getDeclarations(ASTTypeDeclaration.class).any(ASTTypeDeclaration::isRegularClass);
			boolean hasInstanceField = t.getDeclarations(ASTFieldDeclaration.class).any(f -> !f.isStatic());
			return hasRecord && !hasPlainNestedClass && !hasInstanceField;
		}

		private static String owner(Node n) {
			ASTTypeDeclaration t = n.ancestors(ASTTypeDeclaration.class).first();
			return t == null ? "?" : t.getSimpleName();
		}

		private static String where(Node n) {
			Path file = Path.of(n.getReportLocation().getFileId().getAbsolutePath());
			return TARGET.relativize(file) + ":" + n.getReportLocation().getStartLine();
		}

		private static String module(Node n) {
			Path file = Path.of(n.getReportLocation().getFileId().getAbsolutePath());
			return MODULES.stream().filter(m -> file.startsWith(m.sourceRoot())).map(Module::path).findFirst().orElse(".");
		}
	}

	// ---- CPD, in-process ------------------------------------------------------------------------

	static List<Item> cpd(Path target, List<Module> modules, String javaVersion, int minimumTokens, int top) throws IOException {
		List<Item> blocks = new ArrayList<>();
		StringBuilder perModule = new StringBuilder();
		for (Module module : modules) {
			CPDConfiguration config = new CPDConfiguration();
			config.setMinimumTileSize(minimumTokens);
			config.setDefaultLanguageVersion(JavaLanguageModule.getInstance().getVersion(javaVersion));
			config.setSkipLexicalErrors(true);
			int before = blocks.size();
			try (CpdAnalysis cpd = CpdAnalysis.create(config)) {
				cpd.files().addDirectory(module.sourceRoot());
				cpd.performAnalysis(report -> {
					for (Match m : report.getMatches()) {
						String marks = m.getMarkSet().stream().map(mark -> mark(target, mark)).sorted()
								.collect(Collectors.joining(" = "));
						blocks.add(new Item(m.getMarkCount() + " copies, " + m.getLineCount() + " lines", marks, module.path(), m.getTokenCount()));
					}
				});
			}
			int found = blocks.size() - before;
			if (found > 0) {
				perModule.append(module.path()).append(": ").append(found).append("; ");
			}
		}
		Metric m = new Metric("cpd", "Duplicate blocks (CPD), tokens", "cpd.minimumTokens", 100, blocks);
		System.out.println("## " + m.title() + "   [" + m.rule() + "]");
		System.out.println();
		System.out.println("CPD ran at " + minimumTokens + " tokens, per module, as the gate runs it (a block copied between modules is not seen).");
		if (blocks.isEmpty()) {
			System.out.println("No duplicate block at " + minimumTokens + " tokens or more.");
			System.out.println();
			return blocks;
		}
		System.out.println("Blocks per module: " + perModule);
		System.out.println();
		topTable(blocks, top, "tokens");
		System.out.println("Reference " + m.reference() + " tokens: " + m.failing() + " blocks fail; each is fixed by extraction or excluded with a reason.");
		System.out.println();
		return blocks;
	}

	private static String mark(Path target, Mark mark) {
		Path file = Path.of(mark.getLocation().getFileId().getAbsolutePath());
		return target.relativize(file) + ":" + mark.getLocation().getStartLine();
	}

	// ---- Reporting and the fit check --------------------------------------------------------------

	/** Prints the metric's section; returns whether the reference fits. */
	static boolean report(Metric m, int top) {
		System.out.println("## " + m.title() + "   [" + m.rule() + "]");
		System.out.println();
		if (m.items().isEmpty()) {
			System.out.println("Nothing measured.");
			System.out.println();
			return true;
		}
		int[] sorted = m.items().stream().mapToInt(Item::value).sorted().toArray();
		System.out.printf(Locale.ROOT, "n=%d  p50=%d  p90=%d  p95=%d  p99=%d  max=%d%n", sorted.length,
				percentile(sorted, 50), percentile(sorted, 90), percentile(sorted, 95), percentile(sorted, 99),
				sorted[sorted.length - 1]);
		System.out.println();
		topTable(m.items(), top, "value");
		return fit(m);
	}

	static void topTable(List<Item> items, int top, String valueHeader) {
		System.out.println("| " + valueHeader + " | name | where |");
		System.out.println("|---:|---|---|");
		items.stream().sorted(Comparator.comparingInt(Item::value).reversed().thenComparing(Item::name)).limit(top)
				.forEach(i -> System.out.println("| " + i.value() + " | " + i.name() + " | " + i.where() + " |"));
		System.out.println();
	}

	/**
	 * The fit check, which replaced a gap-search "knee" after the second code base. A gap search
	 * in the tail of a heavy-tailed metric finds 1.3x gaps everywhere and, searching above the
	 * reference, proposes sparing exactly the worst code; measured on the MCP Java SDK, every such
	 * proposal loosened the gate. So the threshold is the kit's reference, a constant across code
	 * bases, and what this prints is its cost and whether it fits: a reference fits when at most
	 * {@value #FIT_PERCENT} percent of the population fails, which is the shape "the worst code
	 * fails, ordinary code passes". A worse fit is a stop for the owner: the reference does not
	 * describe this code base, and either the code base is unusual or the reference is wrong.
	 */
	static boolean fit(Metric m) {
		int r = m.reference();
		int n = m.items().size();
		long failing = m.failing();
		double percent = 100.0 * failing / n;
		boolean fits = failing * 100 <= (long) FIT_PERCENT * n;
		System.out.printf(Locale.ROOT, "Reference %d: %d of %d fail (%.1f%%). %s%n", r, failing, n, percent,
				fits ? "Fits: the threshold stays at " + r + "; the cost is the " + failing + " above."
						: "**Does not fit** (more than " + FIT_PERCENT + "% fail): see the STOP block below.");
		System.out.println();
		return fits;
	}

	static int percentile(int[] sorted, int p) {
		int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
		return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
	}

	/** Class-NCSS items minus the types the owner exempted (the ruleset carries the matching exclusion). */
	static List<Item> applyClassSizeExemptions(List<Item> items, Decisions d) {
		Set<String> exempt = d.classSizeExempt();
		if (exempt.isEmpty()) {
			return items;
		}
		return items.stream().filter(i -> !exempt.contains(simpleName(i.name()))).collect(Collectors.toList());
	}

	static String simpleName(String binaryName) {
		int dot = binaryName.lastIndexOf('.');
		return dot < 0 ? binaryName : binaryName.substring(dot + 1);
	}

	// ---- Stops ------------------------------------------------------------------------------------

	/**
	 * The stops for the owner, one block per missing decision. Each block carries its ID, the
	 * data that raised it, the options, a recommendation computed from the data, and the exact
	 * line to record in {@code config/guardrails/decisions.md}. The agent relays a block verbatim
	 * and does not proceed past it.
	 */
	static void stops(Path target, List<Module> all, List<Module> gated, List<Metric> metrics, List<Metric> unfit,
			List<Item> cpdBlocks, Decisions d) {
		List<String> blocks = new ArrayList<>();

		List<Module> testSupport = all.stream().filter(m -> !m.testSupportReasons().isEmpty()).collect(Collectors.toList());
		if (!testSupport.isEmpty() && !d.has("TEST_SUPPORT_MODULES")) {
			StringBuilder b = new StringBuilder("STOP TEST_SUPPORT_MODULES\n");
			b.append("  These modules keep test code in src/main/java, so the gate would make the agent refactor tests:\n");
			for (Module m : testSupport) {
				long pmd = metrics.stream().flatMap(x -> x.items().stream().filter(i -> i.module().equals(m.path()) && i.value() >= x.reference())).count();
				long cpd = cpdBlocks.stream().filter(i -> i.module().equals(m.path()) && i.value() >= 100).count();
				b.append("    ").append(m.path()).append(": ").append(String.join("; ", m.testSupportReasons()))
						.append(" (").append(pmd).append(" PMD, ").append(cpd).append(" CPD at the reference)\n");
			}
			String names = testSupport.stream().map(Module::path).collect(Collectors.joining(", "));
			b.append("  Options: (a) leave them out of the gate   (b) gate them as published code\n");
			b.append("  Recommendation: (a)\n");
			b.append("  Record as:  TEST_SUPPORT_MODULES: exclude ").append(names).append("\n");
			b.append("          or  TEST_SUPPORT_MODULES: gate\n");
			blocks.add(b.toString());
		}

		if (!d.has("PMD_MECHANISM")) {
			Map<String, Long> perModule = new TreeMap<>();
			for (Metric m : metrics) {
				m.items().stream().filter(i -> i.value() >= m.reference()).forEach(i -> perModule.merge(i.module(), 1L, Long::sum));
			}
			long total = perModule.values().stream().mapToLong(Long::longValue).sum();
			long cpd = cpdBlocks.stream().filter(i -> i.value() >= 100).count();
			StringBuilder b = new StringBuilder("STOP PMD_MECHANISM\n");
			b.append("  Cost of going green in the measured modules: ").append(total).append(" PMD violations and ").append(cpd).append(" CPD blocks");
			if (!testSupport.isEmpty() && !d.has("TEST_SUPPORT_MODULES")) {
				b.append(" (counts change if TEST_SUPPORT_MODULES excludes modules; answer that first)");
			}
			b.append(".\n");
			perModule.forEach((mod, n) -> b.append("    ").append(mod).append(": ").append(n).append(" PMD\n"));
			b.append("  Options: (a) fix-all: every violation fixed in the code, one hotspot per commit\n");
			b.append("           (b) ratchet: maxAllowedViolations at today's count per module, lowered as fixes land\n");
			b.append("           (c) scope: gate the listed modules first, the rest later\n");
			b.append("  Recommendation: ").append(total <= FIX_ALL_LIMIT ? "(a), the count is below " + FIX_ALL_LIMIT : "(b), the count is above " + FIX_ALL_LIMIT).append("\n");
			b.append("  Record as:  PMD_MECHANISM: fix-all\n");
			b.append("          or  PMD_MECHANISM: ratchet\n");
			b.append("          or  PMD_MECHANISM: scope ").append(gated.stream().map(Module::path).collect(Collectors.joining(", "))).append("\n");
			blocks.add(b.toString());
		}

		List<ClassSizeCase> undecided = CLASS_SIZE_CASES.values().stream()
				.filter(c -> !d.classSizeExempt().contains(c.simpleName()) && !d.classSizeRefactor().contains(c.simpleName()))
				.collect(Collectors.toList());
		if (!undecided.isEmpty()) {
			StringBuilder b = new StringBuilder("STOP CLASS_SIZE\n");
			b.append("  These types fail class NCSS (").append(CLASS_NCSS_REFERENCE).append(") for a reason other than tangled code:\n");
			for (ClassSizeCase c : undecided) {
				b.append("    ").append(c.binaryName()).append(": ").append(c.shape()).append("\n");
				b.append("      recommendation: ").append(c.recommendation()).append("\n");
			}
			b.append("  Options per type: exempt (a written structural exclusion in the ruleset)  |  refactor\n");
			b.append("  Record as:  CLASS_SIZE: ").append(undecided.stream()
					.map(c -> (c.recommendation().startsWith("exempt") ? "exempt " : "refactor ") + c.simpleName()).collect(Collectors.joining("; "))).append("\n");
			blocks.add(b.toString());
		}

		for (Metric m : unfit) {
			if (d.has("THRESHOLD_FIT_" + m.key().toUpperCase(Locale.ROOT).replace('-', '_'))) {
				continue;
			}
			StringBuilder b = new StringBuilder("STOP THRESHOLD_FIT_" + m.key().toUpperCase(Locale.ROOT).replace('-', '_') + "\n");
			b.append("  ").append(m.title()).append(": ").append(m.failing()).append(" of ").append(m.items().size())
					.append(" fail at the reference ").append(m.reference()).append(", more than ").append(FIT_PERCENT).append("%.\n");
			b.append("  The reference was calibrated on acp-java and confirmed on the MCP Java SDK; here it does not separate the worst code from the rest.\n");
			b.append("  Options: (a) accept: the code base is unusual, gate at the reference and ratchet   (b) the reference is wrong for this kind of code base: record it as evidence and gate at a value you choose\n");
			b.append("  Recommendation: (a) unless the failing items read as ordinary code\n");
			b.append("  Record as:  THRESHOLD_FIT_").append(m.key().toUpperCase(Locale.ROOT).replace('-', '_')).append(": accept\n");
			b.append("          or  THRESHOLD_FIT_").append(m.key().toUpperCase(Locale.ROOT).replace('-', '_')).append(": reference-wrong <value>\n");
			blocks.add(b.toString());
		}

		System.out.println("## Stops");
		System.out.println();
		if (blocks.isEmpty()) {
			System.out.println("None: every decision this measurement needs is recorded in " + target.relativize(Decisions.path(target)) + ".");
			if (d.has("PMD_MECHANISM") && d.get("PMD_MECHANISM").startsWith("ratchet")) {
				System.out.println();
				System.out.println("Ratchet values (maxAllowedViolations per module at today's count):");
				Map<String, Long> perModule = new TreeMap<>();
				for (Metric m : metrics) {
					m.items().stream().filter(i -> i.value() >= m.reference()).forEach(i -> perModule.merge(i.module(), 1L, Long::sum));
				}
				perModule.forEach((mod, n) -> System.out.println("  " + mod + ": " + n));
			}
			System.out.println();
			return;
		}
		System.out.println("Relay each block below to the owner as written, record the answer in `"
				+ target.relativize(Decisions.path(target)) + "`, then run measure again.");
		System.out.println();
		System.out.println("```");
		for (String b : blocks) {
			System.out.print(b);
			System.out.println();
		}
		System.out.println("```");
	}

	// ---- Decisions file ---------------------------------------------------------------------------

	/** {@code config/guardrails/decisions.md} in the target: one {@code KEY: value} line per decision. */
	static final class Decisions {
		private static final Pattern LINE = Pattern.compile("^([A-Z][A-Z0-9_]+):\\s*(.*?)\\s*$");
		private final Map<String, String> values = new LinkedHashMap<>();
		private final Path file;

		private Decisions(Path file) {
			this.file = file;
		}

		static Path path(Path target) {
			return target.resolve("config").resolve("guardrails").resolve("decisions.md");
		}

		static Decisions read(Path target) throws IOException {
			Decisions d = new Decisions(path(target));
			if (Files.isRegularFile(d.file)) {
				for (String line : Files.readAllLines(d.file)) {
					Matcher m = LINE.matcher(line);
					if (m.matches()) {
						// a trailing "(2026-10-08)" is the date the decision was taken, not part of the value
						d.values.put(m.group(1), m.group(2).replaceFirst("\\s*\\([^)]*\\)\\s*$", ""));
					}
				}
			}
			return d;
		}

		boolean has(String key) {
			return values.containsKey(key);
		}

		String get(String key) {
			return values.getOrDefault(key, "");
		}

		List<String> excludedModules() {
			String v = get("TEST_SUPPORT_MODULES");
			if (!v.startsWith("exclude")) {
				return List.of();
			}
			return Stream.of(v.substring("exclude".length()).split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
		}

		Set<String> classSizeExempt() {
			return classSize("exempt");
		}

		Set<String> classSizeRefactor() {
			return classSize("refactor");
		}

		/** {@code CLASS_SIZE: exempt McpSchema; refactor McpServer} */
		private Set<String> classSize(String verb) {
			Set<String> names = new HashSet<>();
			for (String part : get("CLASS_SIZE").split(";")) {
				String p = part.trim();
				if (p.startsWith(verb + " ")) {
					for (String n : p.substring(verb.length() + 1).split(",")) {
						names.add(n.trim());
					}
				}
			}
			return names;
		}

		void print() {
			if (values.isEmpty()) {
				System.out.println("Decisions: none recorded yet (" + file + ").");
			}
			else {
				System.out.println("Decisions from `" + file + "`:");
				values.forEach((k, v) -> System.out.println("  " + k + ": " + v));
			}
			System.out.println();
		}
	}

	// ---- Target discovery -----------------------------------------------------------------------

	/**
	 * Every module below the target that has a src/main/java, with the reasons it looks like test
	 * support: its name, test libraries on its compile classpath, or most of its classes named
	 * like tests. Build output and vendored trees are skipped.
	 */
	static List<Module> modules(Path target) throws IOException {
		List<Path> roots;
		try (Stream<Path> walk = Files.walk(target)) {
			roots = walk.filter(Files::isDirectory)
					.filter(p -> p.endsWith(Path.of("src", "main", "java")))
					.filter(p -> !p.toString().contains("/target/") && !p.toString().contains("/build/")
							&& !p.toString().contains("/.git/") && !p.toString().contains("/node_modules/"))
					.sorted().collect(Collectors.toList());
		}
		List<Module> modules = new ArrayList<>();
		for (Path root : roots) {
			Path moduleDir = root.getParent().getParent().getParent();
			String path = target.equals(moduleDir) ? "." : target.relativize(moduleDir).toString();
			modules.add(new Module(path, root, testSupportReasons(path, moduleDir, root)));
		}
		return modules;
	}

	static List<String> testSupportReasons(String path, Path moduleDir, Path root) throws IOException {
		List<String> reasons = new ArrayList<>();
		if (TEST_MODULE_NAME.matcher(path).find()) {
			reasons.add("named like a test module");
		}
		Path pom = moduleDir.resolve("pom.xml");
		if (Files.isRegularFile(pom)) {
			String xml = Files.readString(pom);
			List<String> compileScoped = new ArrayList<>();
			for (String dep : xml.split("<dependency>")) {
				Matcher m = TEST_LIBRARY.matcher(dep);
				if (m.find() && !dep.contains("<scope>test</scope>")) {
					compileScoped.add(m.group(1));
				}
			}
			if (!compileScoped.isEmpty()) {
				reasons.add("test libraries on the compile classpath: " + String.join(", ", compileScoped));
			}
		}
		List<Path> classes;
		try (Stream<Path> walk = Files.walk(root)) {
			classes = walk.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("package-info.java")).collect(Collectors.toList());
		}
		long testNamed = classes.stream().filter(p -> p.getFileName().toString().matches(".*(Test|Tests|IT)\\.java")).count();
		if (!classes.isEmpty() && testNamed * 2 >= classes.size()) {
			reasons.add(testNamed + " of " + classes.size() + " classes named like tests");
		}
		return reasons;
	}

	static int intOption(String[] args, String name, int dflt) {
		for (int i = 0; i < args.length - 1; i++) {
			if (args[i].equals(name)) {
				return Integer.parseInt(args[i + 1]);
			}
		}
		return dflt;
	}

	/** A comma-separated option, e.g. {@code --exclude mcp-test,conformance-tests}; empty if absent. */
	static List<String> listOption(String[] args, String name) {
		for (int i = 0; i < args.length - 1; i++) {
			if (args[i].equals(name)) {
				return List.of(args[i + 1].split(","));
			}
		}
		return List.of();
	}

	static <T> List<T> sync() {
		return Collections.synchronizedList(new ArrayList<>());
	}
}
