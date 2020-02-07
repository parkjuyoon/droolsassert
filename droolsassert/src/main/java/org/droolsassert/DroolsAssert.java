package org.droolsassert;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Integer.parseInt;
import static java.lang.Long.MAX_VALUE;
import static java.lang.String.format;
import static java.lang.System.out;
import static java.time.LocalTime.MIDNIGHT;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.sort;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.collections4.CollectionUtils.subtract;
import static org.apache.commons.lang3.ObjectUtils.firstNonNull;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.apache.commons.lang3.StringUtils.join;
import static org.apache.commons.lang3.builder.ToStringBuilder.reflectionToString;
import static org.apache.commons.lang3.builder.ToStringStyle.SHORT_PREFIX_STYLE;
import static org.apache.commons.lang3.math.NumberUtils.INTEGER_ZERO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.runners.model.MultipleFailureException.assertEmpty;
import static org.kie.internal.KnowledgeBaseFactory.newKnowledgeSessionConfiguration;
import static org.kie.internal.io.ResourceFactory.newUrlResource;

import java.io.IOException;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import org.drools.core.time.SessionPseudoClock;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.kie.api.KieBase;
import org.kie.api.command.Command;
import org.kie.api.event.rule.BeforeMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.event.rule.DefaultRuleRuntimeEventListener;
import org.kie.api.event.rule.ObjectDeletedEvent;
import org.kie.api.event.rule.ObjectInsertedEvent;
import org.kie.api.event.rule.ObjectUpdatedEvent;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.rule.Agenda;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.internal.utils.KieHelper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

/**
 * JUnit {@link TestRule} for declarative drools tests.
 * 
 * @see <a href=https://github.com/droolsassert/droolsassert>Documentation on GitHub</a>
 */
public class DroolsAssert implements TestRule {
	protected static final DateTimeFormatter HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");
	protected static final DateTimeFormatter HH_MM_SS_SSS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static PathMatcher pathMatcher = new AntPathMatcher();
	private static PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
	protected static Map<DroolsSession, KieBase> kieBases = new WeakHashMap<>();
	
	protected DroolsSession droolsSessionMeta;
	protected AssertRules assertRulesMeta;
	
	protected KieSession session;
	protected Agenda agenda;
	protected SessionPseudoClock clock;
	protected Map<String, Integer> activations = new LinkedHashMap<>();
	protected Map<String, Integer> activationsSnapshot = new LinkedHashMap<>();
	protected Set<String> ignored = new HashSet<>();
	protected Map<Object, Integer> factsHistory = new IdentityHashMap<>();
	protected RulesChronoAgendaEventListener rulesChrono = rulesChrono();
	
	protected void init(KieSession session) {
		this.session = session;
		agenda = session.getAgenda();
		clock = session.getSessionClock();
		session.addEventListener(new LoggingAgendaEventListener());
		session.addEventListener(new LoggingWorkingMemoryEventListener());
		session.addEventListener(rulesChrono);
	}
	
	protected KieSession newSession(DroolsSession droolsSessionMeta) {
		Map<String, String> properties = defaultSessionProperties();
		properties.putAll(toMap(false, checkNotNull(droolsSessionMeta, "Missing @DroolsSession on a class").properties()));
		KieSessionConfiguration config = newKnowledgeSessionConfiguration();
		for (Map.Entry<String, String> property : properties.entrySet())
			config.setProperty(property.getKey(), property.getValue());
		
		return kieBase(droolsSessionMeta).newKieSession(config, null);
	}
	
	protected KieBase kieBase(DroolsSession droolsSessionMeta) {
		if (kieBases.containsKey(droolsSessionMeta))
			return kieBases.get(droolsSessionMeta);
		
		try {
			KieHelper kieHelper = new KieHelper();
			for (Resource resource : getResources(droolsSessionMeta))
				kieHelper.addResource(newUrlResource(resource.getURL()));
			kieBases.put(droolsSessionMeta, kieHelper.build());
			return kieBases.get(droolsSessionMeta);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot create new session", e);
		}
	}
	
	protected final List<Resource> getResources(DroolsSession droolsSessionMeta) throws IOException {
		List<Resource> resources = new ArrayList<>();
		for (String resourceNameFilter : firstNonEmpty(droolsSessionMeta.value(), droolsSessionMeta.resources()))
			resources.addAll(asList(resourceResolver.getResources(resourceNameFilter)));
		checkArgument(resources.size() > 0, "No resources found");
		
		if (droolsSessionMeta.logResources())
			resources.forEach(resource -> out.println(resource));
		return resources;
	}
	
	public KieSession getSession() {
		return session;
	}
	
	/**
	 * Returns an object of the specified class.
	 *
	 * @throws AssertionError
	 *             if object was not found or there are more than one instance of the class
	 */
	@SuppressWarnings("unchecked")
	public <T> T getObject(Class<T> clazz) {
		Collection<T> objects = getObjects(clazz);
		assertFalse(format("No object of type %s found", clazz.getSimpleName()), objects.isEmpty());
		assertFalse(format("Non-unique object of type %s found", clazz.getSimpleName()), objects.size() > 1);
		return (T) objects.toArray()[0];
	}
	
	/**
	 * Returns all objects of the class if found
	 */
	@SuppressWarnings("unchecked")
	public <T> Collection<T> getObjects(Class<T> clazz) {
		return (Collection<T>) session.getObjects(obj -> clazz.isInstance(obj));
	}
	
	/**
	 * Move clock forward and trigger any scheduled rules.<br>
	 * Use second as a smallest time tick.
	 */
	public void advanceTime(long amount, TimeUnit unit) {
		advanceTime(SECONDS, unit.toSeconds(amount));
	}
	
	/**
	 * Move clock forward and trigger any scheduled rules.<br>
	 * Use time unit as a smallest time tick, make specified amount of ticks.
	 */
	public void advanceTime(TimeUnit unit, long amount) {
		for (int i = 0; i < amount; i++)
			tickTime(1, unit);
	}
	
	/**
	 * Asserts the only rules listed have been activated no more no less.
	 *
	 * @see #assertAllActivations(Map)
	 * @throws AssertionError
	 */
	public void assertAllActivations(String... expected) {
		Map<String, Integer> expectedMap = new LinkedHashMap<>();
		for (String rule : expected)
			expectedMap.put(rule, null);
		assertAllActivations(expectedMap);
	}
	
	/**
	 * Asserts the only rules listed have been activated no more no less.<br>
	 * Accepts the number of activations to assert.
	 * 
	 * @see #assertAllActivations(String...)
	 * @throws AssertionError
	 */
	public void assertAllActivations(Map<String, Integer> expectedCount) {
		assertActivations(expectedCount, activations);
	}
	
	/**
	 * Asserts the only rules listed have been activated no more no less <i>since previous check</i>.
	 *
	 * @see #assertActivated(Map)
	 * @see #awaitFor(String...)
	 * @throws AssertionError
	 */
	public void assertActivated(String... expected) {
		Map<String, Integer> expectedMap = new HashMap<>();
		for (String rule : expected)
			expectedMap.put(rule, null);
		assertActivated(expectedMap);
	}
	
	/**
	 * Asserts the only rules listed have been activated no more no less <i>since previous check</i>.<br>
	 * Accepts the number of activations to assert.
	 *
	 * @see #assertActivated(String...)
	 * @see #awaitFor(String...)
	 * @throws AssertionError
	 */
	public void assertActivated(Map<String, Integer> expectedCount) {
		Map<String, Integer> delta = getNewActivations(activationsSnapshot);
		activationsSnapshot = new LinkedHashMap<>(activations);
		assertActivations(expectedCount, delta);
	}
	
	protected final void assertActivations(Map<String, Integer> expectedActivations, Map<String, Integer> actualActiavtions) {
		List<String> missing = subtract(expectedActivations.keySet(), actualActiavtions.keySet()).stream()
				.filter(this::isEligibleForAssertion).collect(toList());
		List<String> extra = subtract(actualActiavtions.keySet(), expectedActivations.keySet()).stream()
				.filter(this::isEligibleForAssertion).collect(toList());
		
		if (!missing.isEmpty() && !extra.isEmpty())
			fail(formatUnexpectedCollection("Activation", "not triggered", missing) + LF + formatUnexpectedCollection("Activation", "triggered", extra));
		else if (!missing.isEmpty())
			fail(formatUnexpectedCollection("Activation", "not triggered", missing));
		else if (!extra.isEmpty())
			fail(formatUnexpectedCollection("Activation", "triggered", extra));
		
		for (Entry<String, Integer> actual : actualActiavtions.entrySet()) {
			Integer expected = expectedActivations.get(actual.getKey());
			if (expected != null && !expected.equals(actual.getValue()))
				fail(format("'%s' should be activated %s time(s) but actually it was activated %s time(s)", actual.getKey(), expected, actual.getValue()));
		}
	}
	
	/**
	 * Move clock forward until all listed will not triggered, fail if any of the rule was not triggered before threshold.<br>
	 * Use second as a smallest time tick and a day as a threshold.<br>
	 * It is imperative that all other activations which were part of the same agenda were also triggered, see below.
	 * <p>
	 * <i>Drools Developer's Cookbook (c):</i><br>
	 * People quite often misunderstand how Drools works internally. So, let's try to clarify how rules are "executed" really. Each time an object is inserted/updated/retracted in the working memory, or the facts are update/retracted within the rules, the rules are re-evaluated with the new working
	 * memory state. If a rule matches, it generates an Activation object. This Activation object is stored inside the Agenda until the fireAllRules() method is invoked. These objects are also evaluated when the WorkingMemory state changes to be possibly cancelled. Finally, when the fireAllRules()
	 * method is invoked the Agenda is cleared, executing the associated rule consequence of each Activation object.
	 * 
	 * @see #awaitForAny()
	 * @see #awaitFor(TimeUnit, long, String...)
	 * @see #triggerAllScheduledActivations()
	 * @throws AssertionError
	 *             if expected activation(s) was not be triggered within a day
	 */
	public void awaitFor(String... rulesToWait) {
		awaitFor(SECONDS, DAYS.toSeconds(1), rulesToWait);
	}
	
	/**
	 * Move clock forward until any upcoming scheduled activation will be triggered, fail if no rule was triggered before threshold.<br>
	 * Use second as a smallest time tick and a day as a threshold.
	 * 
	 * @see #awaitFor(String...)
	 * @throws AssertionError
	 *             if no rule was triggered within a day
	 */
	public void awaitForAny() {
		awaitFor(SECONDS, DAYS.toSeconds(1));
	}
	
	/**
	 * Move clock forward until all listed or any upcoming scheduled activation (if list is empty) will be triggered, fail if any of the rule was not triggered before threshold.<br>
	 * Use time unit as a smallest time tick, make specified amount of ticks at maximum.
	 * 
	 * @see #awaitFor(String...)
	 * @see #triggerAllScheduledActivations()
	 * @throws AssertionError
	 *             if expected activation(s) will not be triggered within time period
	 */
	public void awaitFor(TimeUnit unit, long maxCount, String... rulesToWait) {
		Map<String, Integer> activationsSnapshot = new HashMap<>(activations);
		List<String> rules = asList(rulesToWait);
		for (int i = 0; i < maxCount; i++) {
			tickTime(1, unit);
			if (rules.isEmpty() && !getNewActivations(activationsSnapshot).isEmpty()
					|| !rules.isEmpty() && getNewActivations(activationsSnapshot).keySet().containsAll(rules))
				return;
		}
		
		fail(rules.isEmpty()
				? "Expected at least one scheduled activation"
				: formatUnexpectedCollection("Activations", "not scheduled", subtract(rules, getNewActivations(activationsSnapshot).keySet())));
	}
	
	/**
	 * Assert no activations will be triggered in future assuming no new facts
	 * 
	 * @see #triggerAllScheduledActivations()
	 * @throws AssertionError
	 */
	public void assertNoScheduledActivations() {
		Map<String, Integer> activationsSnapshot = new HashMap<>(activations);
		triggerAllScheduledActivations();
		List<String> diff = getNewActivations(activationsSnapshot).keySet().stream().filter(this::isEligibleForAssertion).collect(toList());
		assertTrue(formatUnexpectedCollection("Activation", "scheduled", diff), diff.isEmpty());
	}
	
	protected final void tickTime(long amount, TimeUnit unit) {
		clock.advanceTime(amount, unit);
		// https://issues.jboss.org/browse/DROOLS-2240
		session.fireAllRules();
	}
	
	/**
	 * Trigger all scheduled activations if any
	 * 
	 * @see #awaitFor(String...)
	 * @see #awaitFor(TimeUnit, long, String...)
	 * @see #assertNoScheduledActivations()
	 */
	protected final void triggerAllScheduledActivations() {
		clock.advanceTime(MAX_VALUE, MILLISECONDS);
		session.fireAllRules();
		clock.advanceTime(-MAX_VALUE, MILLISECONDS);
	}
	
	/**
	 * New activations (delta) since previous check.
	 */
	protected final Map<String, Integer> getNewActivations(Map<String, Integer> activationsSnapshot) {
		Map<String, Integer> newActivations = new LinkedHashMap<>();
		for (Entry<String, Integer> activation : activations.entrySet()) {
			if (!activationsSnapshot.containsKey(activation.getKey()))
				newActivations.put(activation.getKey(), activation.getValue());
			else if (activation.getValue() > activationsSnapshot.get(activation.getKey()))
				newActivations.put(activation.getKey(), activation.getValue() - activationsSnapshot.get(activation.getKey()));
		}
		return newActivations;
	}
	
	/**
	 * Asserts object(s) presence in drools knowledge base.
	 * 
	 * @throws AssertionError
	 */
	public void assertExist(Object... objects) {
		Map<Object, Void> identityMap = new IdentityHashMap<>();
		stream(objects).forEach(obj -> identityMap.put(obj, null));
		
		if (droolsSessionMeta.keeFactsHistory()) {
			List<String> unknown = stream(objects).filter(obj -> !factsHistory.containsKey(obj)).map(this::factToString).collect(toList());
			assertTrue(formatUnexpectedCollection("Object", "never inserted into the session", unknown), unknown.isEmpty());
		}
		
		session.getObjects().stream().forEach(obj -> identityMap.remove(obj));
		List<String> retracted = identityMap.keySet().stream().map(this::factToString).collect(toList());
		assertTrue(formatUnexpectedCollection("Object", "removed from the session", retracted), retracted.isEmpty());
	}
	
	/**
	 * Asserts object(s) retracted from knowledge base.
	 * 
	 * @throws AssertionError
	 */
	public void assertRetracted(Object... objects) {
		Map<Object, Void> identityMap = new IdentityHashMap<>();
		stream(objects).forEach(obj -> identityMap.put(obj, null));
		
		if (droolsSessionMeta.keeFactsHistory()) {
			List<String> unknown = stream(objects).filter(obj -> !factsHistory.containsKey(obj)).map(this::factToString).collect(toList());
			assertTrue(formatUnexpectedCollection("Object", "never inserted into the session", unknown), unknown.isEmpty());
		}
		
		List<String> notRetracted = session.getObjects().stream().filter(obj -> identityMap.containsKey(obj)).map(this::factToString).collect(toList());
		assertTrue(formatUnexpectedCollection("Object", "not retracted from the session", notRetracted), notRetracted.isEmpty());
	}
	
	/**
	 * Asserts all objects were retracted from knowledge base.
	 * 
	 * @throws AssertionError
	 */
	public void assertAllRetracted() {
		List<String> facts = session.getObjects().stream().map(this::factToString).collect(toList());
		assertTrue(formatUnexpectedCollection("Object", "not retracted from the session", facts), facts.isEmpty());
	}
	
	/**
	 * Asserts exact count of facts in knowledge base.
	 * 
	 * @throws AssertionError
	 */
	public void assertFactsCount(long factsCount) {
		assertEquals(factsCount, session.getFactCount());
	}
	
	/**
	 * Define rules to be ignored while any assertions.
	 */
	public void ignoreActivations(String... rulePatterns) {
		ignored.addAll(asList(rulePatterns));
	}
	
	/**
	 * Define global variables for drools session
	 */
	public void setGlobal(String identifier, Object value) {
		session.setGlobal(identifier, value);
	}
	
	/**
	 * @see KieSession#execute(Command)
	 */
	public <T> T execute(Command<T> command) {
		return session.execute(command);
	}
	
	/**
	 * @see KieSession#insert(Object)
	 */
	public List<FactHandle> insert(Object... objects) {
		List<FactHandle> factHandles = new LinkedList<>();
		for (Object object : objects)
			factHandles.add(session.insert(object));
		return factHandles;
	}
	
	/**
	 * @see KieSession#fireAllRules()
	 */
	public int fireAllRules() {
		out.println(formatTime() + " --> fireAllRules");
		return session.fireAllRules();
	}
	
	/**
	 * Insert all objects listed and fire all rules after each
	 * 
	 * @see KieSession#insert(Object)
	 * @see KieSession#fireAllRules()
	 */
	public List<FactHandle> insertAndFire(Object... objects) {
		List<FactHandle> result = new LinkedList<>();
		for (Object object : objects) {
			result.addAll(insert(object));
			fireAllRules();
		}
		return result;
	}
	
	/**
	 * Print retained facts in insertion order
	 * 
	 * @see DroolsSession#keeFactsHistory()
	 */
	public void printFacts() {
		if (!droolsSessionMeta.keeFactsHistory())
			return;
		
		List<Object> sortedFacts = new LinkedList<>(session.getObjects());
		sort(sortedFacts, (o1, o2) -> factsHistory.get(o1).compareTo(factsHistory.get(o2)));
		out.println(format("%s Facts (%s):", formatTime(), session.getFactCount()));
		for (Object fact : sortedFacts)
			out.println(factToString(fact));
	}
	
	public void printPerformanceStatistic() {
		out.println(format("%s Performance Statistic, total activations %s:", formatTime(), activations.values().stream().mapToInt(Integer::intValue).sum()));
		rulesChrono.getPerfStat().values()
				.forEach(s -> out.printf("%s - min: %.2f avg: %.2f max: %.2f activations: %d%n", s.getDomain(), s.getMinTimeMs(), s.getAvgTimeMs(), s.getMaxTimeMs(), s.getLeapsCount()));
	}
	
	@Override
	public Statement apply(Statement base, Description description) {
		droolsSessionMeta = description.getTestClass().getAnnotation(DroolsSession.class);
		assertRulesMeta = description.getAnnotation(AssertRules.class);
		if (assertRulesMeta == null)
			return base;
		
		init(newSession(droolsSessionMeta));
		
		return new Statement() {
			@Override
			public void evaluate() throws Throwable {
				DroolsAssert.this.evaluate(base);
			}
		};
	}
	
	protected void evaluate(Statement base) throws Throwable {
		List<Throwable> errors = new ArrayList<>();
		try {
			base.evaluate();
		} catch (Throwable th) {
			errors.add(th);
		}
		try {
			ignoreActivations(droolsSessionMeta.ignoreRules());
			ignoreActivations(assertRulesMeta.ignore());
			if (assertRulesMeta.checkScheduled())
				triggerAllScheduledActivations();
			if (assertRulesMeta.expectedCount().length != 0)
				assertAllActivations(toMap(true, assertRulesMeta.expectedCount()));
			else
				assertAllActivations(firstNonEmpty(assertRulesMeta.value(), assertRulesMeta.expected()));
		} catch (Throwable th) {
			errors.add(0, th);
		}
		rulesChrono.reset();
		session.dispose();
		assertEmpty(errors);
	}
	
	protected Map<String, String> defaultSessionProperties() {
		return toMap(false, "drools.eventProcessingMode", "stream", "drools.clockType", "pseudo");
	}
	
	protected RulesChronoAgendaEventListener rulesChrono() {
		return rulesChrono = new RulesChronoAgendaEventListener();
	}
	
	@SuppressWarnings("unchecked")
	public <T extends RulesChronoAgendaEventListener> T getRulesChrono() {
		return (T) rulesChrono;
	}
	
	public void setRulesChrono(RulesChronoAgendaEventListener rulesChrono) {
		session.removeEventListener(this.rulesChrono);
		session.addEventListener(rulesChrono);
		this.rulesChrono = rulesChrono;
	}
	
	protected boolean isEligibleForAssertion(String rule) {
		return !ignored.stream().filter(pattern -> pathMatcher.match(pattern, rule)).findFirst().isPresent();
	}
	
	protected String factToString(Object fact) {
		return fact instanceof String ? (String) fact : reflectionToString(fact, SHORT_PREFIX_STYLE);
	}
	
	protected String tupleToString(List<Object> tuple) {
		return "" + tuple.stream().map(o -> o.getClass().getSimpleName()).collect(toList());
	}
	
	protected final String formatUnexpectedCollection(String entityName, String message, Collection<String> entities) {
		return format("%s%s %s:%n%s", entityName, entities.size() == 1 ? " was" : "s were", message, join(entities, LF));
	}
	
	protected final String formatTime() {
		return MIDNIGHT.plus(Duration.ofMillis(clock.getCurrentTime() == MAX_VALUE ? -1 : clock.getCurrentTime()))
				.format(clock.getCurrentTime() == MAX_VALUE || clock.getCurrentTime() % 1000 == 0 ? HH_MM_SS : HH_MM_SS_SSS);
	}
	
	@SuppressWarnings("unchecked")
	private <T> Map<String, T> toMap(boolean convertToInt, String... params) {
		checkArgument(params.length % 2 == 0, "Cannot create a map out of odd number of parameters");
		Map<String, T> map = new LinkedHashMap<>();
		for (int i = 0; i < params.length; i = i + 2)
			map.put(params[i], (T) (convertToInt ? parseInt(params[i + 1]) : params[i + 1]));
		return map;
	}
	
	private String[] firstNonEmpty(String[]... params) {
		for (String[] param : params) {
			if (param.length != 0)
				return param;
		}
		return new String[0];
	}
	
	private class LoggingAgendaEventListener extends DefaultAgendaEventListener {
		@Override
		public void beforeMatchFired(BeforeMatchFiredEvent event) {
			String ruleName = event.getMatch().getRule().getName();
			activations.put(ruleName, firstNonNull(activations.get(ruleName), INTEGER_ZERO) + 1);
			out.printf("%s <-- '%s' has been activated by the tuple %s%n", formatTime(), ruleName, tupleToString(event.getMatch().getObjects()));
		}
	}
	
	private class LoggingWorkingMemoryEventListener extends DefaultRuleRuntimeEventListener {
		@Override
		public void objectInserted(ObjectInsertedEvent event) {
			Object fact = event.getObject();
			if (droolsSessionMeta.keeFactsHistory() && !factsHistory.containsKey(fact))
				factsHistory.put(fact, factsHistory.size());
			
			out.println(formatTime() + " --> inserted: " + (droolsSessionMeta.logFacts() ? factToString(fact) : fact.getClass().getSimpleName()));
		}
		
		@Override
		public void objectDeleted(ObjectDeletedEvent event) {
			Object fact = event.getOldObject();
			out.println(formatTime() + " --> retracted: " + (droolsSessionMeta.logFacts() ? factToString(fact) : fact.getClass().getSimpleName()));
		}
		
		@Override
		public void objectUpdated(ObjectUpdatedEvent event) {
			out.println(formatTime() + " --> updated: " + (droolsSessionMeta.logFacts()
					? format("%s%nto: %s", factToString(event.getOldObject()), factToString(event.getObject()))
					: event.getOldObject().getClass().getSimpleName()));
		}
	}
}