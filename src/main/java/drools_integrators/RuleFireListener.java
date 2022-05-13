package drools_integrators;

import org.apache.commons.math3.util.Pair;
import org.drools.core.common.InternalFactHandle;
import org.drools.core.event.DefaultAgendaEventListener;
import org.drools.core.reteoo.AbstractTerminalNode;
import org.drools.core.reteoo.RuleTerminalNodeLeftTuple;
import org.kie.api.definition.rule.Rule;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.BeforeMatchFiredEvent;
import org.kie.kogito.explainability.model.Output;
import org.kie.kogito.explainability.model.Type;
import org.kie.kogito.explainability.model.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static drools_integrators.BeanReflectors.beanProperties;
import static drools_integrators.ReteTraverser.parseTerminalNode;

public class RuleFireListener extends DefaultAgendaEventListener {
    private Map<String, Map<String, Object>> beforeHashes = new HashMap<>();
    private Map<String, Map<String, Object>> afterHashes = new HashMap<>();
    private Map<Rule, Map<String, Pair<Object, Object>>> differences = new HashMap<>();
    private Set<String> excludedRules = new HashSet<>();
    private Set<String> includedRules = new HashSet<>();
    private Set<String> includedOutputContainers = new HashSet<>();
    private Set<String> excludedOutputContainers = new HashSet<>();
    private Set<String> actualIncludedRules = new HashSet<>();
    private Set<String> actualIncludedContainers = new HashSet<>();
    private Set<String> actualExcludedContainers = new HashSet<>();
    private List<InternalFactHandle> eventFactHandles;
    private final ParserContext droolsParserContext;
    private int inputNumber = 0;
    private List<Pair<Rule, String>> outputTargets;
    private HashMap<Pair<Rule, String>, Output> desiredOutputs = new HashMap<>();
    private boolean parseGraph;

    public Map<String, Map<String, Object>> getBeforeHashes() {
        return beforeHashes;
    }

    public void setBeforeHashes(Map<String, Map<String, Object>> beforeHashes) {
        this.beforeHashes = beforeHashes;
    }

    public Map<String, Map<String, Object>> getAfterHashes() {
        return afterHashes;
    }

    public void setAfterHashes(Map<String, Map<String, Object>> afterHashes) {
        this.afterHashes = afterHashes;
    }

    public Map<Rule, Map<String, Pair<Object, Object>>> getDifferences() {
        return differences;
    }

    public void setDifferences(Map<Rule, Map<String, Pair<Object, Object>>> differences) {
        this.differences = differences;
    }

    public Set<String> getExcludedRules() {
        return excludedRules;
    }

    public void setExcludedRules(Set<String> excludedRules) {
        this.excludedRules = excludedRules;
    }

    public Set<String> getIncludedRules() {
        return includedRules;
    }

    public void setIncludedRules(Set<String> includedRules) {
        this.includedRules = includedRules;
    }

    public Set<String> getIncludedOutputContainers() {
        return includedOutputContainers;
    }

    public void setIncludedOutputContainers(Set<String> includedOutputContainers) {
        this.includedOutputContainers = includedOutputContainers;
    }

    public Set<String> getExcludedOutputContainers() {
        return excludedOutputContainers;
    }

    public void setExcludedOutputContainers(Set<String> excludedOutputContainers) {
        this.excludedOutputContainers = excludedOutputContainers;
    }

    public Set<String> getActualIncludedRules() {
        return actualIncludedRules;
    }

    public void setActualIncludedRules(Set<String> actualIncludedRules) {
        this.actualIncludedRules = actualIncludedRules;
    }

    public Set<String> getActualIncludedContainers() {
        return actualIncludedContainers;
    }

    public void setActualIncludedContainers(Set<String> actualIncludedContainers) {
        this.actualIncludedContainers = actualIncludedContainers;
    }

    public Set<String> getActualExcludedContainers() {
        return actualExcludedContainers;
    }

    public void setActualExcludedContainers(Set<String> actualExcludedContainers) {
        this.actualExcludedContainers = actualExcludedContainers;
    }

    public List<InternalFactHandle> getEventFactHandles() {
        return eventFactHandles;
    }

    public void setEventFactHandles(List<InternalFactHandle> eventFactHandles) {
        this.eventFactHandles = eventFactHandles;
    }

    public ParserContext getDroolsParserContext() {
        return droolsParserContext;
    }

    public int getInputNumber() {
        return inputNumber;
    }

    public List<Pair<Rule, String>> getOutputTargets() {
        return outputTargets;
    }

    public HashMap<Pair<Rule, String>, Output> getDesiredOutputs() {
        return desiredOutputs;
    }

    public void setDesiredOutputs(HashMap<Pair<Rule, String>, Output> desiredOutputs) {
        this.desiredOutputs = desiredOutputs;
    }

    public boolean isParseGraph() {
        return parseGraph;
    }

    public void setParseGraph(boolean parseGraph) {
        this.parseGraph = parseGraph;
    }

    public RuleFireListener(Set<String> includedRules, Set<String> excludedRules, Set<String> includedOutputContainers,
                            Set<String> excludedOutputContainers, ParserContext droolsParserContext, boolean parseGraph) {
        this.includedRules = includedRules;
        this.excludedRules = excludedRules;
        this.includedOutputContainers = includedOutputContainers;
        this.excludedOutputContainers = excludedOutputContainers;
        this.droolsParserContext = droolsParserContext;
        this.parseGraph = parseGraph;
    }

    // which # input to the model is this from?
    public void setInputNumber(int inputNumber) {
        this.inputNumber = inputNumber;
    }

    // how do access the object that is our desired output?
    public void setOutputTargets(List<Pair<Rule, String>> outputTargets) {
        this.outputTargets = outputTargets;
    }

    // set desired output as a TrustyAI Object
    public void addDesiredOutput(Pair<Rule, String> outputTarget, Object o) {
        String name = outputTarget.getFirst().getName() + ": " + outputTarget.getSecond();
        if (o instanceof Number) {
            desiredOutputs.put(outputTarget, new Output(name, Type.NUMBER, new Value(((Number) o).doubleValue()), 1.0));
        } else if (o instanceof Boolean) {
            desiredOutputs.put(outputTarget, new Output(name, Type.BOOLEAN, new Value(o), 1.0));
        } else if (o instanceof String) {
            desiredOutputs.put(outputTarget, new Output(name, Type.CATEGORICAL, new Value(o), 1.0));
        }
    }

    // does this particular Rule satisfy the inclusion/exclusion requirements
    public boolean ruleInclusionCheck(String ruleName) {
        boolean includeThisRule = true;
        if (includedRules.size() > 0) {
            includeThisRule = includedRules.stream().anyMatch(ruleName::contains);
        }
        if (excludedRules.size() > 0) {
            includeThisRule &= excludedRules.stream().noneMatch(ruleName::contains);
        }
        return includeThisRule;
    }

    // before a rule fires, catalog every object associated with the rule
    @Override
    public void beforeMatchFired(BeforeMatchFiredEvent event) {
        super.beforeMatchFired(event);
        List<InternalFactHandle> eventFactHandles = (List<InternalFactHandle>) event.getMatch().getFactHandles();
        this.eventFactHandles = eventFactHandles;
        if (ruleInclusionCheck(event.getMatch().getRule().getName())) {
            for (InternalFactHandle fh : eventFactHandles) {
                beforeHashes.put(fh.getObject().getClass().getName() + "_" + fh.hashCode(), beanProperties(fh.getObject(), this));
            }
        }
    }

    // after the rule fires, catalog every object associated with the rule
    // then, see what has changed
    @Override
    public void afterMatchFired(AfterMatchFiredEvent event) {
        super.afterMatchFired(event);
        if (ruleInclusionCheck(event.getMatch().getRule().getName())) {
            List<InternalFactHandle> eventFactHandles = (List<InternalFactHandle>) event.getMatch().getFactHandles();
            for (InternalFactHandle fh : eventFactHandles) {
                afterHashes.put(fh.getObject().getClass().getName() + "_" + fh.hashCode(), beanProperties(fh.getObject(), this));
            }

            Set<String> keySets = new HashSet<>(afterHashes.keySet());
            keySets.addAll(beforeHashes.keySet());

            for (String key : new ArrayList<>(keySets)) {
                if (!beforeHashes.containsKey(key)) {
                    Pair<Object, Object> differenceObject = new Pair<>(null, afterHashes.get(key));
                    Utils.addToHashOfHashOfPair(this.differences, event.getMatch().getRule(), key, differenceObject);
                }
                if (!afterHashes.containsKey(key)) {
                    Pair<Object, Object> differenceObject = new Pair<>(beforeHashes.get(key), null);
                    Utils.addToHashOfHashOfPair(this.differences, event.getMatch().getRule(), key, differenceObject);
                }
                if (beforeHashes.containsKey(key) && afterHashes.containsKey(key)) {
                    Map<String, Object> afterValues = afterHashes.get(key);
                    Map<String, Object> beforeValues = beforeHashes.get(key);

                    Set<String> objectKeySets = new HashSet<>(afterValues.keySet());
                    objectKeySets.addAll(new ArrayList<>(beforeValues.keySet()));

                    for (String objectKey : objectKeySets) {
                        if (!beforeValues.containsKey(objectKey)) {
                            Pair<Object, Object> differenceObject = new Pair<>(null, afterValues.get(objectKey));
                            Utils.addToHashOfHashOfPair(this.differences, event.getMatch().getRule(), objectKey, differenceObject);
                        }
                        if (!afterValues.containsKey(objectKey)) {
                            Pair<Object, Object> differenceObject = new Pair<>(beforeValues.get(objectKey), null);
                            Utils.addToHashOfHashOfPair(this.differences, event.getMatch().getRule(), objectKey, differenceObject);
                        }
                        if (beforeValues.containsKey(objectKey) && afterValues.containsKey(objectKey)) {
                            if (!beforeValues.get(objectKey).equals(afterValues.get(objectKey))) {
                                Pair<Object, Object> differenceObject = new Pair<>(beforeValues.get(objectKey), afterValues.get(objectKey));
                                Utils.addToHashOfHashOfPair(this.differences, event.getMatch().getRule(), objectKey, differenceObject);
                            }
                        }
                        beforeHashes.remove(key);
                        afterHashes.remove(key);
                    }
                }
            }

            // if we've set an output target, capture it here
            if (outputTargets != null) {
                for (Pair<Rule, String> outputTarget : outputTargets) {
                    if (event.getMatch().getRule() == outputTarget.getFirst()) {
                        if (differences.get(outputTarget.getFirst()).containsKey(outputTarget.getSecond())) {
                            this.addDesiredOutput(outputTarget, differences.get(outputTarget.getFirst()).get(outputTarget.getSecond()).getSecond());
                        } else {
                            this.addDesiredOutput(outputTarget, null);
                        }
                    }
                }
            }

            if (parseGraph) {
                RuleContext ruleContext = new RuleContext(event.getMatch().getRule(),
                        event.hashCode(),
                        inputNumber,
                        eventFactHandles);
                parseTerminalNode((AbstractTerminalNode) ((RuleTerminalNodeLeftTuple) event.getMatch()).getTupleSink(), this, ruleContext, droolsParserContext);
            }
        }
    }
}
