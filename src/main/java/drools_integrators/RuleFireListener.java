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
import java.util.function.Predicate;

import static drools_integrators.BeanReflectors.beanProperties;
import static drools_integrators.ReteTraverser.parseTerminalNode;
import static drools_integrators.Utils.printGraph;

public class RuleFireListener extends DefaultAgendaEventListener {
    private Map<String, Map<String, Object>> lifespanHashes = new HashMap<>();
    private Map<String, Map<String, Object>> beforeHashes = new HashMap<>();
    private Map<String, Map<String, Object>> afterHashes = new HashMap<>();
    private Map<Rule, Map<String, Pair<Object, Object>>> differences = new HashMap<>();

    private Set<String> excludedRules = new HashSet<>();
    private Set<String> includedRules = new HashSet<>();
    private Set<String> includedOutputFields = new HashSet<>();
    private Set<String> excludedOutputFields = new HashSet<>();
    private Set<String> includedOutputObjects = new HashSet<>();
    private Set<String> excludedOutputObjects = new HashSet<>();
    private Set<String> actualIncludedRules = new HashSet<>();
    private Set<String> actualExcludedRules = new HashSet<>();
    private Set<String> actualIncludedObjects = new HashSet<>();
    private Set<String> actualExcludedObjects = new HashSet<>();
    private Set<String> actualIncludedFields = new HashSet<>();
    private Set<String> actualExcludedFields = new HashSet<>();

    private final ParserContext droolsParserContext;
    private int inputNumber = 0;
    private List<Pair<Rule, String>> outputTargets;
    private HashMap<Pair<Rule, String>, Output> desiredOutputs = new HashMap<>();
    private final boolean parseGraph;

    public Map<Rule, Map<String, Pair<Object, Object>>> getDifferences() {
        return differences;
    }

    public Set<String> getIncludedOutputObjects() {
        return includedOutputObjects;
    }

    public Set<String> getExcludedOutputObjects() {
        return excludedOutputObjects;
    }

    public Set<String> getActualIncludedRules() {
        return actualIncludedRules;
    }

    public Set<String> getActualIncludedObjects() {
        return actualIncludedObjects;
    }

    public Set<String> getActualExcludedObjects() {
        return actualExcludedObjects;
    }

    public Set<String> getExcludedRules() { return excludedRules;}

    public Set<String> getIncludedRules() {return includedRules;}

    public Set<String> getIncludedOutputFields() {return includedOutputFields;}

    public Set<String> getExcludedOutputFields() {return excludedOutputFields;}

    public Set<String> getActualExcludedRules() {return actualExcludedRules;}

    public Set<String> getActualIncludedFields() {return actualIncludedFields;}

    public Set<String> getActualExcludedFields() {return actualExcludedFields;}

    public HashMap<Pair<Rule, String>, Output> getDesiredOutputs() {
        return desiredOutputs;
    }


    public RuleFireListener(Set<String> includedRules, Set<String> excludedRules,
                            Set<String> includedOutputFields, Set<String> excludedOutputFields,
                            Set<String> includedOutputObjects, Set<String> excludedOutputObjects,
                            ParserContext droolsParserContext, boolean parseGraph) {
        this.includedRules = includedRules;
        this.excludedRules = excludedRules;
        this.includedOutputObjects = includedOutputObjects;
        this.excludedOutputObjects = excludedOutputObjects;
        this.includedOutputFields = includedOutputFields;
        this.excludedOutputFields = excludedOutputFields;
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

    private boolean generalInclusionCheck(String name, Set<String> included, Set<String> excluded, Set<String> inclusionTracker, Set<String> exclusionTracker, boolean contains){
        boolean include = true;
        Predicate<String> predicate = contains ? name::contains : name::equals;

        if (!included.isEmpty()) {
            include= includedRules.stream().anyMatch(predicate);
        }
        if (!excluded.isEmpty()) {
            include &= excluded.stream().noneMatch(predicate);
        }
        if (include) {
            inclusionTracker.add(name);
        } else {
            exclusionTracker.add(name);
        }
        return include;
    }

    // does this particular Rule satisfy the inclusion/exclusion requirements
    public boolean ruleInclusionCheck(String ruleName) {
        return generalInclusionCheck(ruleName, includedRules, excludedRules, actualIncludedRules, actualExcludedRules, true);
    }

    public boolean objectInclusionCheck(String objectName) {
        return generalInclusionCheck(objectName, includedOutputObjects, excludedOutputObjects, actualIncludedObjects, actualExcludedObjects, true);
    }

    public boolean fieldInclusionCheck(String fieldName) {
        return generalInclusionCheck(fieldName, includedOutputFields, excludedOutputFields, actualIncludedFields, actualExcludedFields, true);
    }

    // before a rule fires, catalog every object associated with the rule
    @Override
    public void beforeMatchFired(BeforeMatchFiredEvent event) {
        super.beforeMatchFired(event);
        List<InternalFactHandle> eventFactHandles = (List<InternalFactHandle>) event.getMatch().getFactHandles();
        if (ruleInclusionCheck(event.getMatch().getRule().getName())) {
            for (InternalFactHandle fh : eventFactHandles) {
                if (objectInclusionCheck(fh.getObject().getClass().getName())) {
                    Map<String, Object> objectProperties = beanProperties(fh.getObject(), this);
                    String key = fh.getObject().getClass().getName() + "_" + fh.hashCode();
//                    for (String subkey : objectProperties.keySet()) {
//                        if (! lifespanHashes.containsKey(subkey)) {
//                            Pair<Object, Object> differenceObject = new Pair<>(null, objectProperties.get(subkey));
//                            Utils.addToHashOfHashOfPair(this.differences, event.getMatch().getRule(), subkey, differenceObject);
//                            lifespanHashes.put(subkey, objectProperties);
//                        }
//                    }
                    beforeHashes.put(key, objectProperties);
                }
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
                if (objectInclusionCheck(fh.getObject().getClass().getName())) {
                    afterHashes.put(fh.getObject().getClass().getName() + "_" + fh.hashCode(), beanProperties(fh.getObject(), this));
                }
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

            if (parseGraph) {
                RuleContext ruleContext = new RuleContext(event.getMatch().getRule(),
                        event.hashCode(),
                        inputNumber,
                        eventFactHandles);
                parseTerminalNode((AbstractTerminalNode) ((RuleTerminalNodeLeftTuple) event.getMatch()).getTupleSink(), this, ruleContext, droolsParserContext);
            }

            // if we've set an output target, capture it here
            if (outputTargets != null) {
                for (Pair<Rule, String> outputTarget : outputTargets) {
                    if (event.getMatch().getRule() == outputTarget.getFirst()) {
                        if (differences.get(outputTarget.getFirst()).containsKey(outputTarget.getSecond())) {
                            this.addDesiredOutput(outputTarget, differences.get(outputTarget.getFirst()).get(outputTarget.getSecond()).getSecond());
                        }
                    }
                }
            }
        }
    }
}
