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

import javax.sound.midi.SysexMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static drools_integrators.BeanReflectors.beanProperties;
import static drools_integrators.ReteTraverser.parseTerminalNode;

public class RuleFireListener extends DefaultAgendaEventListener {
    private Set<Integer> lifespanHashes = new HashSet<>();
    private Map<String, Map<String, Object>> beforeHashes = new HashMap<>();
    private Map<String, Map<String, Object>> afterHashes = new HashMap<>();

    private Map<OutputAccessor, OutputCandidate> differences = new HashMap<>();

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
    private List<OutputAccessor> outputAccessors;
    private HashMap<OutputAccessor, Output> desiredOutputs = new HashMap<>();
    private final boolean parseGraph;

    public Map<OutputAccessor, OutputCandidate> getDifferences() {
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

    public HashMap<OutputAccessor, Output> getDesiredOutputs() {
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
    public void setOutputTargets(List<OutputAccessor> outputAccessors) {
        this.outputAccessors = outputAccessors;
    }

    // set desired output as a TrustyAI Object
    public void addDesiredOutput(OutputAccessor outputAccessor, Object o) {
        String name = outputAccessor.rule.getName() + ": " + outputAccessor.name;
        if (o instanceof Number) {
            desiredOutputs.put(outputAccessor, new Output(name, Type.NUMBER, new Value(((Number) o).doubleValue()), 1.0));
        } else if (o instanceof Boolean) {
            desiredOutputs.put(outputAccessor, new Output(name, Type.BOOLEAN, new Value(o), 1.0));
        } else if (o instanceof String) {
            desiredOutputs.put(outputAccessor, new Output(name, Type.CATEGORICAL, new Value(o), 1.0));
        } else {
            desiredOutputs.put(outputAccessor, new Output(name, Type.UNDEFINED, new Value(o), 1.0));
        }
    }

    private boolean generalInclusionCheck(String name, Set<String> included, Set<String> excluded, Set<String> inclusionTracker, Set<String> exclusionTracker, boolean contains){
        boolean include = true;
        Predicate<String> predicate = contains ? name::contains : name::equals;
        if (!included.isEmpty()) {
            include= included.stream().anyMatch(predicate);
        }
        if (!excluded.isEmpty()) {
            include &= excluded.stream().noneMatch(predicate);
        }
        if (include) {
            inclusionTracker.add(name);
        } else {
            exclusionTracker.add(name);
        }
        //System.out.printf("%s | In inclusions: %s | In exclusions: %s %n", name, included.stream().anyMatch(predicate), excluded.stream().noneMatch(predicate) );
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
                    beforeHashes.put(key, objectProperties);
                }
            }
        }
    }

    public void checkDifferences(AfterMatchFiredEvent event){
        Set<String> keySets = new HashSet<>(afterHashes.keySet());
        keySets.addAll(beforeHashes.keySet());
        for (String key : new ArrayList<>(keySets)) {
            OutputAccessor outputAccessor = new OutputAccessor(event.getMatch().getRule(), key);
            if (!beforeHashes.containsKey(key)) {
                OutputCandidate differenceObject = new OutputCandidate(null, afterHashes.get(key));
                this.differences.put(outputAccessor, differenceObject);
            }
            if (!afterHashes.containsKey(key)) {
                OutputCandidate differenceObject = new OutputCandidate(beforeHashes.get(key), null);
                this.differences.put(outputAccessor, differenceObject);
            }
            if (beforeHashes.containsKey(key) && afterHashes.containsKey(key)) {
                if (!lifespanHashes.contains(Objects.hash(outputAccessor, key))) {
                    OutputCandidate differenceObject = new OutputCandidate(null, "Created", true);
                    outputAccessor.setObjectExistence(true);
                    this.differences.put(outputAccessor, differenceObject);
                }
                Map<String, Object> afterValues = afterHashes.get(key);
                Map<String, Object> beforeValues = beforeHashes.get(key);

                Set<String> objectKeySets = new HashSet<>(afterValues.keySet());
                objectKeySets.addAll(new ArrayList<>(beforeValues.keySet()));

                for (String objectKey : objectKeySets) {
                    outputAccessor = new OutputAccessor(event.getMatch().getRule(), objectKey);
                    //System.out.printf("checking "+objectKey + " " + afterValues.get(objectKey).toString() +": ");
                    if (!beforeValues.containsKey(objectKey)) {
                        OutputCandidate differenceObject = new OutputCandidate(null, afterValues.get(objectKey));
                        //System.out.println(" not in before");
                        this.differences.put(outputAccessor, differenceObject);
                    }
                    if (!afterValues.containsKey(objectKey)) {
                        //System.out.println(" not in after");
                        OutputCandidate differenceObject = new OutputCandidate(beforeValues.get(objectKey), null);
                        this.differences.put(outputAccessor, differenceObject);
                    }
                    if (beforeValues.containsKey(objectKey) && afterValues.containsKey(objectKey)) {
                        //System.out.printf(" in both: ");
                        if (!beforeValues.get(objectKey).equals(afterValues.get(objectKey))) {
                            //System.out.println(" update");
                            OutputCandidate differenceObject = new OutputCandidate(beforeValues.get(objectKey), afterValues.get(objectKey));
                            this.differences.put(outputAccessor, differenceObject);
                        } else if (!lifespanHashes.contains(Objects.hash(outputAccessor, objectKey))){
                            //System.out.printf(" not update, %s not in lifespan %s %n", objectKey, lifespanHashes);
                            OutputCandidate differenceObject = new OutputCandidate(null, afterValues.get(objectKey));
                            this.differences.put(outputAccessor, differenceObject);
                            lifespanHashes.add(Objects.hash(outputAccessor, objectKey));
                        } else if (this.differences.containsKey(outputAccessor) && !this.differences.get(outputAccessor).after.equals(afterValues.get(objectKey))) {
                            OutputCandidate differenceObject = new OutputCandidate( this.differences.get(outputAccessor).before, afterValues.get(objectKey));
                            this.differences.put(outputAccessor, differenceObject);
                            //System.out.println(" no rulediff update");
                        } else {
                            //System.out.printf(" not update %s , in lifespan%n", objectKey);
                        }
                    }

                }
                lifespanHashes.add(Objects.hash(outputAccessor, key));
                beforeHashes.remove(key);
                afterHashes.remove(key);
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

            checkDifferences(event);
            //System.out.println(differences);
            if (parseGraph) {
                RuleContext ruleContext = new RuleContext(event.getMatch().getRule(),
                        event.hashCode(),
                        inputNumber,
                        eventFactHandles);
                parseTerminalNode((AbstractTerminalNode) ((RuleTerminalNodeLeftTuple) event.getMatch()).getTupleSink(), this, ruleContext, droolsParserContext);
            }

            // if we've set an output target, capture it here
            if (outputAccessors != null) {
                //System.out.println("== OUTPUT EXTRACTION ==");
                for (OutputAccessor outputAccessor : outputAccessors) {
                    //System.out.printf("  %s: %s %s %s ", outputAccessor, outputAccessor.objectExistence, desiredOutputs.containsKey(outputAccessor), event.getMatch().getRule());
                    if (outputAccessor.objectExistence && !desiredOutputs.containsKey(outputAccessor)){
                        this.addDesiredOutput(outputAccessor, "Not Created");
                        //System.out.printf("creating fallback | ");
                    } else if (!desiredOutputs.containsKey(outputAccessor)){
                        this.addDesiredOutput(outputAccessor, null);
                        //System.out.printf("creating null out | ");
                    }
                    if (event.getMatch().getRule() == outputAccessor.rule) {
                        if (differences.containsKey(outputAccessor)){
                            //System.out.printf("actual out | ");
                            this.addDesiredOutput(outputAccessor, differences.get(outputAccessor).after);
                        } else {
                            //System.out.printf("missed case 1 | ");
                        }
                    } else {
                        //System.out.printf("missed case 2 | ");
                    }
                }
                //System.out.println();
                //System.out.println(desiredOutputs);
            }
        }
    }
}
