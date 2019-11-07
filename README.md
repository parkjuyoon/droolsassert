## Goal

Relieve Drools JUnit testing 

## Audience

If you find yourself writing too much boilerplate code to initialize drools sessions rather than writing actual test scenarios    
or it is rather hard to understand rules triggering order and cause-effect dependencies between the rules in your session,  
this library may help you spend less time debugging, make tests neat and support easier.  

## Approach

Unit test is about taking minimum piece of code and test all possible usecases defining specification. With integration tests your goal is not all possible usecases but integration of several units that work together. Do the same with rules. Segregate rules by business meaning and purpose. Simplest 'unit under the test' could be file with single or [high cohension](https://stackoverflow.com/questions/10830135/what-is-high-cohesion-and-how-to-use-it-make-it) set of rules and what is required for it to work (if any), like common dsl definition file and decision table. For integration test you could take meaningful subset or all rules of the system. 

With this approach you'll have many isolated unit tests which will not be impacted and will not require support when you add new rules and few integration tests with limited amount of common input data to reproduce and test 'common scenarios'. Adding new rules to integration test will require to update expected results and will reflect how new rules impact common data flow.

## Usage

Specify any combination of rules you want to test in single session using `@DroolsSession`, `logResources` to see what was actually included.  
Spring ant-like PathMatchingResourcePatternResolver gives you robust tool to include functionality you want to test together or segregate.  

	@DroolsSession(resources = {
		"classpath*:/org/droolsassert/rules.drl",
		"classpath*:/com/company/project/*/{regex:.*.(drl|dsl|xlsx|gdst)}",
		"classpath*:/com/company/project/*/ruleUnderTest.rdslr" },
		logResources = true)

Declare the rule for the test

	@Rule
	public DroolsAssert drools = new DroolsAssert();

Test which rules were triggered in declarative way with `@AssertRules` annotation in addition to assertions inside test method and use other useful utilities to deal with the session.

	@Test
	@AssertRules("atomic int rule")
	public void testInt() {
		drools.insertAndFire(new AtomicInteger());
		assertEquals(1, drools.getObject(AtomicInteger.class).get());
	}

## Examples

[Dummy assertions](https://github.com/droolsassert/droolsassert/wiki/Dummy-assertions)  
[Logical events](https://github.com/droolsassert/droolsassert/wiki/Logical-events)  
[Spring integration test 1](https://github.com/droolsassert/droolsassert/wiki/Spring-integration-test-1)  
[Spring integration test 2](https://github.com/droolsassert/droolsassert/wiki/Spring-integration-test-2)  
[Extend it with your application specific utilities](https://github.com/droolsassert/droolsassert/wiki/Extension-example)  

## Latest maven builds

For Drools 7.x  

    <dependency>
        <groupId>org.droolsassert</groupId>
        <artifactId>droolsassert</artifactId>
        <version>1.7.9</version>
        <scope>test</scope>
    </dependency>

For Drools 6.x  

    <dependency>
        <groupId>org.droolsassert</groupId>
        <artifactId>droolsassert</artifactId>
        <version>1.6.9</version>
        <scope>test</scope>
    </dependency>
