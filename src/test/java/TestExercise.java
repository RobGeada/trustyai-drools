import cost.*;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.util.Pair;
import org.drools.core.base.ClassFieldReader;
import org.drools.core.common.DefaultFactHandle;
import org.drools.core.common.InternalFactHandle;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.event.DefaultAgendaEventListener;
import org.drools.core.reteoo.AbstractTerminalNode;
import org.drools.core.reteoo.AccumulateNode;
import org.drools.core.reteoo.AlphaNode;
import org.drools.core.reteoo.BetaNode;
import org.drools.core.reteoo.EntryPointNode;
import org.drools.core.reteoo.JoinNode;
import org.drools.core.reteoo.LeftInputAdapterNode;
import org.drools.core.reteoo.LeftTupleSource;
import org.drools.core.reteoo.NotNode;
import org.drools.core.reteoo.ObjectSource;
import org.drools.core.reteoo.ObjectTypeNode;
import org.drools.core.reteoo.RightInputAdapterNode;
import org.drools.core.reteoo.RuleTerminalNode;
import org.drools.core.reteoo.RuleTerminalNodeLeftTuple;
import org.drools.core.spi.BetaNodeFieldConstraint;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;
import org.kie.api.definition.rule.Rule;
import org.drools.mvel.MVELConstraint;
import org.junit.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.BeforeMatchFiredEvent;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieContainerSessionsPool;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.kogito.explainability.model.Feature;
import org.kie.kogito.explainability.model.FeatureFactory;
import org.kie.kogito.explainability.model.Output;
import org.kie.kogito.explainability.model.PredictionInput;
import org.kie.kogito.explainability.model.PredictionOutput;
import org.kie.kogito.explainability.model.PredictionProvider;
import org.kie.kogito.explainability.model.Type;
import org.kie.kogito.explainability.model.Value;
import org.kie.kogito.explainability.utils.CompositeFeatureUtils;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.supplyAsync;

@SuppressWarnings("restriction")
public class TestExercise {
    Random rn = new Random(0);
    KieServices ks = KieServices.Factory.get();
    KieContainer kieContainer = ks.getKieClasspathContainer();


    // class to represent nodes in the rete graph
    class GraphNode {
        String type;
        int id;
        RuleContext ruleContext;
        Integer[] calls = {0, 0};
        Set<Integer> callIds = new HashSet<>();
        boolean uniqueRules = false;
        Object value;
        String field;

        Set<Integer> inputNumbers = new HashSet<>();
        boolean finalized = false;


        public GraphNode(String type, RuleContext ruleContext, int id){
            this.type = type;
            this.ruleContext = ruleContext;
            this.id = id;
            this.calls[ruleContext.inputNumber] = 1;
            inputNumbers.add(ruleContext.inputNumber);
            this.value = null;
            this.field = "";
        }

        public GraphNode(String type, RuleContext ruleContext, int id, String field, Object value){
            this.type = type;
            this.ruleContext = ruleContext;
            this.id = id;
            this.calls[ruleContext.inputNumber] = 1;
            inputNumbers.add(ruleContext.inputNumber);
            this.value = value;
            this.field = field;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GraphNode graphNode = (GraphNode) o;
            if (this.type.contains("Terminal")) {
                return id == graphNode.id
                        && Objects.equals(type, graphNode.type)
                        && (Objects.equals(ruleContext.inputNumber, graphNode.ruleContext.inputNumber) || Objects.equals(value, graphNode.value))
                        && Objects.equals(field, graphNode.field)
                        && Objects.equals(ruleContext.rule.getName(), graphNode.ruleContext.rule.getName());
            } else {
                return id == graphNode.id
                        && Objects.equals(type, graphNode.type)
                        && Objects.equals(ruleContext.rule.getName(), graphNode.ruleContext.rule.getName());
            }
        }

        @Override
        public int hashCode() {
            if (this.finalized){
                return Objects.hash(type, id, ruleContext.inputNumber, ruleContext.rule, field);
            } else if (this.type.contains("Terminal")) {
                return Objects.hash(type, id, ruleContext.rule, field);
            } else {
                return Objects.hash(type);
            }
        }

        @Override
        public String toString(){
            if (this.value == null){
                return String.format("%s%n%d-%d", this.type, this.calls[0], this.calls[1]);
            } else {
                return String.format("%s%n%s=%s", this.type, this.field, this.value);
            }
        }

        public String getColor(boolean edge){
            if ((this.calls[0]==0 && this.calls[1]==0) || !(this.type.contains("Terminal") || edge)){
                return "white";
            } else {
                float total = this.calls[0] + this.calls[1];
                String red = String.format("%02X", (int) ((this.calls[0] / total) * 255));
                String blue = String.format("%02X", (int) ((this.calls[1] / total) * 255));
                String result = String.format("#%s00%s", red, blue);
                return result;
            }
        }

        public String getShape(){
            if (this.type.contains("Alpha") || this.type.contains("Terminal")){
                return "rectangle";
            } else {
                return "oval";
            }
        }
    }

    /* function to add/merge nodes as necessary
     nodes are MERGED when they already exist in the graph for the same rule or contain the same value (in the case of terminal nodes)
     nodes are ADDED otherwise
     */
    public GraphNode nodeAdd(Graph<GraphNode, DefaultEdge> graph, HashMap<Integer, GraphNode> nodeMap, GraphNode n){
        boolean inGraph = nodeMap.containsKey(n.hashCode());
        Boolean matchingRuleFlow = null;
        Boolean matchingValue = null;
        if (inGraph){
            GraphNode containedNode = nodeMap.get(n.hashCode());
            matchingRuleFlow = containedNode.ruleContext.inputNumber == n.ruleContext.inputNumber;
            matchingValue = Objects.equals(containedNode.value, n.value);
            // node merge?
            if (matchingRuleFlow || matchingValue) {
                containedNode.callIds.add(n.ruleContext.eventHashcode);
                containedNode.calls[n.ruleContext.inputNumber] += 1;
                containedNode.value = n.value;
                return containedNode;
            }
        }
        graph.addVertex(n);
        nodeMap.put(n.hashCode(), n);
        return n;
    }


    // entry point to graph parser. From a terminal node, walk upwards through parent recursively
    public void parseTerminalNode(AbstractTerminalNode terminalNode, RuleTracker ruleTracker, RuleContext ruleContext, DroolsParserContext dpc){
        RuleTerminalNode node = (RuleTerminalNode) terminalNode;
        dpc.currentTerminals = new ArrayList<>();
        if (ruleTracker.differences.containsKey(node.getRule())) {
            for (Map.Entry<String, Pair<Object, Object>> entry : ruleTracker.differences.get(node.getRule()).entrySet()) {
                GraphNode subGraphNode = new GraphNode(
                        "Terminal: " + node.getRule().getName(),
                        ruleContext,
                        node.getId(),
                        entry.getKey(),
                        entry.getValue().getSecond());
                GraphNode addedNode = nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
                parseLeftTupleSource(node.getLeftTupleSource(), subGraphNode, ruleContext, dpc);
                dpc.currentTerminals.add(addedNode);
            }
        } else {
            GraphNode subGraphNode = new GraphNode(
                    "Terminal: " + node.getRule().getName(),
                    ruleContext,
                    node.getId());
            GraphNode addedNode = nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            parseLeftTupleSource(node.getLeftTupleSource(), subGraphNode, ruleContext, dpc);
            dpc.currentTerminals.add(addedNode);
        }
    }

    // from an object source, walk upwards through parent recursively
    public void parseObjectSource(ObjectSource objectSource, GraphNode child, RuleContext ruleContext, DroolsParserContext dpc){
        GraphNode subGraphNode = null;
        GraphNode addedNode = null;
        if (objectSource instanceof AlphaNode) {
            AlphaNode node = (AlphaNode) objectSource;
            MVELConstraint mvelConstraint = (MVELConstraint) node.getConstraint();
            subGraphNode = new GraphNode("Alpha: "+mvelConstraint.getExpression(), ruleContext, node.getId());
            addedNode = nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            parseObjectSource(node.getParentObjectSource(), addedNode, ruleContext, dpc);
        } else if (objectSource instanceof RightInputAdapterNode) {
            RightInputAdapterNode node = (RightInputAdapterNode) objectSource;
            subGraphNode = new GraphNode("RightInput: " + node.getLeftTupleSource().getObjectType().getClassName(), ruleContext, node.getId());
            addedNode = nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            parseLeftTupleSource(node.getLeftTupleSource(), addedNode, ruleContext, dpc);
        } else if (objectSource instanceof EntryPointNode) {
            EntryPointNode node = (EntryPointNode) objectSource;
            subGraphNode = new GraphNode("EntryPoint", ruleContext, node.getId());
            addedNode = nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            for (GraphNode previousTerminalNode : dpc.previousTerminals){
                dpc.graph.addEdge(previousTerminalNode, addedNode);
            }
            dpc.previousTerminals = dpc.currentTerminals;
        } else if (objectSource instanceof ObjectTypeNode){
            ObjectTypeNode objectTypeNode = (ObjectTypeNode) objectSource;
            subGraphNode = new GraphNode("ObjectTypeNode: " + objectTypeNode.getObjectType().getClassName(), ruleContext, objectTypeNode.getId());
            addedNode =nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            parseObjectSource(objectTypeNode.getParentObjectSource(), addedNode, ruleContext, dpc);
        } else {
            System.out.println("ObjectSource other: "+objectSource);
        }
        dpc.graph.addEdge(addedNode, child);
    }

    // from an object source, walk upwards through parent(s) recursively
    public void parseLeftTupleSource(LeftTupleSource leftTupleSinkNode, GraphNode child, RuleContext ruleContext, DroolsParserContext dpc) {
        GraphNode subGraphNode = null;
        BetaNode betaNode = null;
        GraphNode addedNode = null;
        if (leftTupleSinkNode instanceof NotNode) {
            NotNode node = (NotNode) leftTupleSinkNode;
            StringBuilder constraintNames = new StringBuilder();
            for (BetaNodeFieldConstraint bnfc : node.getConstraints()) {
                MVELConstraint mvelConstraint = (MVELConstraint) bnfc;
                constraintNames.append(mvelConstraint.getExpression());
            }
            subGraphNode = new GraphNode("Not " + constraintNames, ruleContext, node.getId());
            betaNode = (BetaNode) node;
        } else if (leftTupleSinkNode instanceof JoinNode) {
            JoinNode node = (JoinNode) leftTupleSinkNode;
            subGraphNode = new GraphNode("Join", ruleContext, node.getId());
            betaNode = (BetaNode) node;
        } else if (leftTupleSinkNode instanceof AccumulateNode) {
            AccumulateNode node = (AccumulateNode) leftTupleSinkNode;
            subGraphNode = new GraphNode("Accumulate", ruleContext, node.getId());
            betaNode = (BetaNode) node;
        } else if (leftTupleSinkNode instanceof LeftInputAdapterNode) {
            LeftInputAdapterNode node = (LeftInputAdapterNode) leftTupleSinkNode;
            subGraphNode = new GraphNode("LeftInput: "+ node.getObjectType().getClassName(), ruleContext, node.getId());
            addedNode = nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            parseObjectSource(node.getObjectSource(), addedNode, ruleContext, dpc);
        } else {
            System.out.println("Left Tuple Source Other:"+ leftTupleSinkNode);
        }

        if (betaNode != null && subGraphNode != null) {
            addedNode = nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            parseObjectSource(betaNode.getRightInput(), addedNode, ruleContext, dpc);
            parseLeftTupleSource(betaNode.getLeftTupleSource(), addedNode, ruleContext, dpc);
        }
        //System.out.println("contains new node "+subGraphNode + ": " + dpc.graph.containsVertex(subGraphNode));
        //System.out.println("contains child node "+subGraphNode + ": " + dpc.graph.containsVertex(child));
        dpc.graph.addEdge(addedNode, child);
    }

    // extract all gettable fields from object recursively into dictionary of field_name:object
    public static HashMap<String, Object> beanProperties(final Object bean, RuleTracker ruleTracker, String prefix, boolean verbose) {
        final HashMap<String, Object> result = new HashMap<>();
        String name = prefix.equals("") ? bean.getClass().getName() : prefix;

        // check if object itself is a "base" type
        if (bean instanceof Number || bean instanceof String || bean instanceof Boolean){
            if (verbose) {System.out.printf("\t %s=%s, primitive? %b", name, bean, true);}
            result.put(name, bean);
            if (verbose){System.out.println("...adding to result");}
            return result;
        }

        // otherwise investigate its contents
        if (verbose){ System.out.println("Exploring "+ name);}
        PropertyDescriptor[] propertyDescriptors = new PropertyDescriptor[0];
        try {
            propertyDescriptors = Introspector.getBeanInfo(bean.getClass(), Object.class).getPropertyDescriptors();
        } catch (Exception ex) {
            // ignore, no property descriptors
        }
        for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
            final Method readMethod = propertyDescriptor.getReadMethod();
            // if there's getters:
            if (readMethod != null) {
                Object read = null;
                try {
                    read = readMethod.invoke(bean, (Object[]) null);
                } catch (Exception ex) {
                    //ignore, non-readable read method
                }
                if (read == null){ return result; }

                String thisName = name + "." + propertyDescriptor.getName();
                boolean inContainers = true;
                if (ruleTracker != null) {
                    if (ruleTracker.includedOutputContainers.size() > 0) {
                        inContainers = ruleTracker.includedOutputContainers.stream().anyMatch(propertyDescriptor.getName()::contains);
                    }
                    if (ruleTracker.excludedOutputContainers.size() > 0) {
                        inContainers &= ruleTracker.excludedOutputContainers.stream().noneMatch(propertyDescriptor.getName()::contains);
                    }
                }

                if (verbose) {
                    System.out.printf("\t %s=%s, primitive? %b, %s in Containers? %b",
                            thisName, read.toString(),
                            (read instanceof Number || read instanceof String || read instanceof Boolean),
                            propertyDescriptor.getName(), inContainers);
                }

                if (ruleTracker != null) {
                    if (inContainers) {
                        ruleTracker.actualIncludedContainers.add(thisName);
                    } else {
                        ruleTracker.actualExcludedContainers.add(thisName);
                    }
                }

                // if the get'ted object is a 'base' type:
                if ((read instanceof Number || read instanceof String || read instanceof Boolean) && inContainers) {
                    result.put(thisName, read);
                    if (verbose) {
                        System.out.println("...adding to result");
                    }
                } else if (read instanceof Iterable && inContainers) { //is is an iterable object?
                    int i = 0;
                    if (verbose) {
                        System.out.printf("%n=== recursing %s ======================%n", name);
                    }
                    for (Object o : (Iterable<?>) read) {
                        beanProperties(
                                o,
                                ruleTracker,
                                thisName + "[" + i + "]",
                                verbose)
                                .forEach(result::putIfAbsent);
                        i++;
                    }
                    if (verbose) {
                        System.out.println("=== end recursion ==================================\n");
                    }
                } else if (inContainers) { // if the object is not base or iterable, but is a specified container:
                    if (verbose) {
                        System.out.println("...unpacking ======================");
                    }
                    beanProperties(
                            read,
                            ruleTracker,
                            thisName,
                            verbose)
                            .forEach(result::putIfAbsent);
                    if (verbose) {
                        System.out.println("=== end unpack ==================================");
                    }
                }
            }
        }

        return result;
    }

    public static void beanPropertiesSetter(final Object bean, HashMap<String, Object> objectHashMap, String prefix, boolean verbose) {
        String name = prefix.equals("") ? bean.getClass().getName() : prefix;

        // otherwise investigate its contents
        if (verbose){ System.out.println("Exploring "+ name);}
        PropertyDescriptor[] propertyDescriptors = new PropertyDescriptor[0];
        try {
            propertyDescriptors = Introspector.getBeanInfo(bean.getClass(), Object.class).getPropertyDescriptors();
        } catch (Exception ex) {
            // ignore, no property descriptors
        }
        for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
            final Method writeMethod = propertyDescriptor.getWriteMethod();
            // if there's getters:
            if (writeMethod != null) {
                try {
                    read = readMethod.invoke(bean, (Object[]) null);
                } catch (Exception ex) {
                    //ignore, non-readable read method
                }
                if (read == null){ return result; }

                String thisName = name + "." + propertyDescriptor.getName();
                boolean inContainers = true;
                if (ruleTracker != null) {
                    if (ruleTracker.includedOutputContainers.size() > 0) {
                        inContainers = ruleTracker.includedOutputContainers.stream().anyMatch(propertyDescriptor.getName()::contains);
                    }
                    if (ruleTracker.excludedOutputContainers.size() > 0) {
                        inContainers &= ruleTracker.excludedOutputContainers.stream().noneMatch(propertyDescriptor.getName()::contains);
                    }
                }

                if (verbose) {
                    System.out.printf("\t %s=%s, primitive? %b, %s in Containers? %b",
                            thisName, read.toString(),
                            (read instanceof Number || read instanceof String || read instanceof Boolean),
                            propertyDescriptor.getName(), inContainers);
                }

                if (ruleTracker != null) {
                    if (inContainers) {
                        ruleTracker.actualIncludedContainers.add(thisName);
                    } else {
                        ruleTracker.actualExcludedContainers.add(thisName);
                    }
                }

                // if the get'ted object is a 'base' type:
                if ((read instanceof Number || read instanceof String || read instanceof Boolean) && inContainers) {
                    result.put(thisName, read);
                    if (verbose) {
                        System.out.println("...adding to result");
                    }
                } else if (read instanceof Iterable && inContainers) { //is is an iterable object?
                    int i = 0;
                    if (verbose) {
                        System.out.printf("%n=== recursing %s ======================%n", name);
                    }
                    for (Object o : (Iterable<?>) read) {
                        beanProperties(
                                o,
                                ruleTracker,
                                thisName + "[" + i + "]",
                                verbose)
                                .forEach(result::putIfAbsent);
                        i++;
                    }
                    if (verbose) {
                        System.out.println("=== end recursion ==================================\n");
                    }
                } else if (inContainers) { // if the object is not base or iterable, but is a specified container:
                    if (verbose) {
                        System.out.println("...unpacking ======================");
                    }
                    beanProperties(
                            read,
                            ruleTracker,
                            thisName,
                            verbose)
                            .forEach(result::putIfAbsent);
                    if (verbose) {
                        System.out.println("=== end unpack ==================================");
                    }
                }
            }
        }

        return result;
    }


    // given a hashmap of hashmaps of pairs, add a Pair to the key $key, subkey $subkey
    // creates the hashmap at $key if necessary
    public <K,S, V> void addToHashOfHashOfPair(HashMap<K, HashMap<S, Pair<V,V>>> map, K key, S subkey, Pair<V,V> value) {
        HashMap<S, Pair<V,V>> hash = map.containsKey(key) ? map.get(key) : new HashMap<>();
        if (hash.containsKey(subkey)){
            Pair<V, V> transitiveObject = new Pair<>(hash.get(subkey).getFirst(), value.getSecond());
            hash.put(subkey, transitiveObject);
        } else {
            hash.put(subkey, value);
        };
        map.put(key, hash);
    }


    //class to track the event + objects associate with a particular Rule
    class RuleContext {
        Rule rule;
        int eventHashcode;

        int inputNumber;
        List<InternalFactHandle> factHandles;

        public RuleContext(Rule rule, int eventHashcode, int inputNumber, List<InternalFactHandle> factHandles) {
            this.rule = rule;
            this.eventHashcode = eventHashcode;
            this.inputNumber = inputNumber;
            this.factHandles = factHandles;
        }
    }


    // AgendaEvent listener, that tracks the objects that change before and after a rule is fired
    class RuleTracker extends DefaultAgendaEventListener {
        HashMap<String, HashMap<String, Object>> beforeHashes = new HashMap<>();
        HashMap<String, HashMap<String, Object>> afterHashes = new HashMap<>();
        HashMap<Rule, HashMap<String, Pair<Object, Object>>> differences = new HashMap<>();
        Set<String> excludedRules = new HashSet<>();
        Set<String> includedRules = new HashSet<>();
        Set<String> includedOutputContainers = new HashSet<>();
        Set<String> excludedOutputContainers = new HashSet<>();


        Set<String> actualIncludedRules = new HashSet<>();
        Set<String> actualIncludedContainers = new HashSet<>();
        Set<String> actualExcludedContainers = new HashSet<>();

        List<InternalFactHandle> eventFactHandles;
        DroolsParserContext droolsParserContext;

        int inputNumber;
        Pair<Rule, String> outputTarget = null;
        Output desiredOutput = null;
        boolean parseGraph;

        public RuleTracker(Set<String> includedRules, Set<String> excludedRules, Set<String> includedOutputContainers,
                           Set<String> excludedOutputContainers,  DroolsParserContext droolsParserContext, boolean parseGraph) {
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
        public void setOutputTarget(Pair<Rule, String> outputTarget){
            this.outputTarget = outputTarget;
        }

        // set desired output as a TrustyAI Object
        public void setDesiredOutput(Object o){
            String name = outputTarget.getFirst().getName() + ": " + outputTarget.getSecond();
            if (o instanceof Number){
                this.desiredOutput = new Output(name, Type.NUMBER, new Value(((Number) o).doubleValue()), 1.0);
            } else if (o instanceof Boolean){
                this.desiredOutput = new Output(name, Type.BOOLEAN, new Value(o), 1.0);
            } else if (o instanceof String){
                this.desiredOutput = new Output(name, Type.CATEGORICAL, new Value(o), 1.0);
            }
        }

        // does this particular Rule satisfy the inclusion/exclusion requirements
        public boolean ruleInclusionCheck(String ruleName){
            boolean includeThisRule = true;
            if (includedRules.size() > 0){
                includeThisRule = includedRules.stream().anyMatch(ruleName::contains);
            }
            if (excludedRules.size() > 0){
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
                    beforeHashes.put(fh.getObject().getClass().getName() + "_" + fh.hashCode(), beanProperties(fh.getObject(), this, "", false));
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
                    afterHashes.put(fh.getObject().getClass().getName() + "_" + fh.hashCode(), beanProperties(fh.getObject(), this, "", false));
                }

                Set<String> keySets = new HashSet<>(afterHashes.keySet());
                keySets.addAll(beforeHashes.keySet());

                for (String key : new ArrayList<>(keySets)) {
                    if (!beforeHashes.containsKey(key)) {
                        Pair<Object, Object> differenceObject = new Pair<>(null, afterHashes.get(key));
                        addToHashOfHashOfPair(this.differences, event.getMatch().getRule(), key, differenceObject);
                    }
                    if (!afterHashes.containsKey(key)) {
                        Pair<Object, Object> differenceObject = new Pair<>(beforeHashes.get(key), null);
                        addToHashOfHashOfPair(this.differences, event.getMatch().getRule(), key, differenceObject);
                    }
                    if (beforeHashes.containsKey(key) && afterHashes.containsKey(key)) {
                        HashMap<String, Object> afterValues = afterHashes.get(key);
                        HashMap<String, Object> beforeValues = beforeHashes.get(key);

                        Set<String> objectKeySets = new HashSet<>(afterValues.keySet());
                        objectKeySets.addAll(new ArrayList<>(beforeValues.keySet()));

                        for (String objectKey : objectKeySets) {
                            if (!beforeValues.containsKey(objectKey)) {
                                Pair<Object, Object> differenceObject = new Pair<>(null, afterValues.get(objectKey));
                                addToHashOfHashOfPair(this.differences, event.getMatch().getRule(), objectKey, differenceObject);
                            }
                            if (!afterValues.containsKey(objectKey)) {
                                Pair<Object, Object> differenceObject = new Pair<>(beforeValues.get(objectKey), null);
                                addToHashOfHashOfPair(this.differences, event.getMatch().getRule(), objectKey, differenceObject);
                            }
                            if (beforeValues.containsKey(objectKey) && afterValues.containsKey(objectKey)) {
                                if (!beforeValues.get(objectKey).equals(afterValues.get(objectKey))) {
                                    Pair<Object, Object> differenceObject = new Pair<>(beforeValues.get(objectKey), afterValues.get(objectKey));
                                    addToHashOfHashOfPair(this.differences, event.getMatch().getRule(), objectKey, differenceObject);
                                }
                            }
                            beforeHashes.remove(key);
                            afterHashes.remove(key);
                        }
                    }
                }

                // if we've set an output target, capture it here
                if (outputTarget !=  null && event.getMatch().getRule() == outputTarget.getFirst() && differences.get(outputTarget.getFirst()).containsKey(outputTarget.getSecond())){
                    this.setDesiredOutput(differences.get(outputTarget.getFirst()).get(outputTarget.getSecond()).getSecond());
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

    // track various objects about the drools engine being parsed, namely the rete graph
    public class DroolsParserContext{
        InternalWorkingMemory internalWorkingMemory;
        Map<String, Value> features;
        Graph<GraphNode, DefaultEdge> graph;
        HashMap<Integer, GraphNode> graphNodeMap;
        List<GraphNode> previousTerminals = new ArrayList<>();
        List<GraphNode> currentTerminals = new ArrayList<>();

        Set<String> excludedFeatureObjects;

        public DroolsParserContext(InternalWorkingMemory internalWorkingMemory, Map<String, Value> features, Graph<GraphNode, DefaultEdge> graph, Set<String> excludedFeatureObjects) {
            this.internalWorkingMemory = internalWorkingMemory;
            this.features = features;
            this.graph = graph;
            this.excludedFeatureObjects = excludedFeatureObjects;
            this.graphNodeMap = new HashMap<>();
        }

        public DroolsParserContext(InternalWorkingMemory internalWorkingMemory, Map<String, Value> features, Graph<GraphNode, DefaultEdge> graph, HashMap<Integer, GraphNode> graphNodeMap, Set<String> excludedFeatureObjects) {
            this.internalWorkingMemory = internalWorkingMemory;
            this.features = features;
            this.graph = graph;
            this.excludedFeatureObjects = excludedFeatureObjects;
            this.graphNodeMap = graphNodeMap;
        }
    }


    @Test
    public void explore() {
        KieSession session = kieContainer.newKieSession("ksession-rules");
        InternalWorkingMemory internalWorkingMemory = (InternalWorkingMemory) session;
        Map<String, Value> features = new HashMap<>();
        Graph<GraphNode, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);

        Set<String> excludedOutputRules = new HashSet<>();//Stream.of("Create Pallet").collect(Collectors.toSet());
        Set<String> includedOutputRules = new HashSet<>(); //Stream.of("CalculateTotal", "Cost").collect(Collectors.toSet());
        Set<String> excludedOutputFields = Stream.of("pallets", "order", "trip", "step", "distance", "transportType", "city", "Step").collect(Collectors.toSet());
        Set<String> includedOutputFields = new HashSet<>();//Stream.of().collect(Collectors.toSet());
        Set<String> excludedFeatureClasses = Stream.of("Pallet").collect(Collectors.toSet());

        DroolsParserContext dpc = new DroolsParserContext(internalWorkingMemory, features, graph, excludedFeatureClasses);
        RuleTracker ruleTracker = new RuleTracker(includedOutputRules, excludedOutputRules, includedOutputFields, excludedOutputFields, dpc, true);
        ruleTracker.setInputNumber(0);
        session.addEventListener(ruleTracker);
        KieBase kbase = session.getKieBase();

        Trip trip = getDefaultTrip();
        Order order = getSampleOrder();
        CostCalculationRequest request = new CostCalculationRequest();
        request.setTrip(trip);
        request.setOrder(order);
        recursiveInsert(session, List.of(request));
        session.startProcess("P1");
        session.fireAllRules();
        session.dispose();

        session = kieContainer.newKieSession("ksession-rules");
        internalWorkingMemory = (InternalWorkingMemory) session;


        dpc = new DroolsParserContext(internalWorkingMemory, features, dpc.graph, dpc.graphNodeMap, excludedFeatureClasses);
        ruleTracker = new RuleTracker(includedOutputRules, excludedOutputRules, includedOutputFields, excludedOutputFields, dpc, true);
        ruleTracker.setInputNumber(1);
        session.addEventListener(ruleTracker);

        order = generateRandomOrders(100, order).get(10);
        request = new CostCalculationRequest();
        request.setTrip(trip);
        request.setOrder(order);
        recursiveInsert(session, List.of(request));
        session.startProcess("P1");
        session.fireAllRules();

        printGraph(graph);
    }

    public void recursiveInsert(KieSession kieSession, List<Object> objectsToInsert){
        for (Object o : objectsToInsert){
            HashMap<String, Object> objectHashMap = beanProperties(o, null, "", false);
            for (Map.Entry<String, Object> entry : objectHashMap.entrySet()){
                kieSession.insert(entry.getValue());
            }
        }

    }


    public RuleTracker runSession(Trip trip, Order order){
        KieSession session = kieContainer.newKieSession("ksession-rules");
        InternalWorkingMemory internalWorkingMemory = (InternalWorkingMemory) session;
        Map<String, Value> features = new HashMap<>();
        Graph<GraphNode, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);

        Set<String> excludedOutputRules = new HashSet<>();//Stream.of("Create Pallet").collect(Collectors.toSet());
        Set<String> includedOutputRules = new HashSet<>(); //Stream.of("CalculateTotal", "Cost").collect(Collectors.toSet());
        Set<String> excludedOutputFields = Stream.of("pallets", "order", "trip", "step", "distance", "transportType", "city", "Step").collect(Collectors.toSet());
        Set<String> includedOutputFields = new HashSet<>();//Stream.of().collect(Collectors.toSet());
        Set<String> excludedFeatureClasses = Stream.of("Pallet").collect(Collectors.toSet());

        DroolsParserContext dpc = new DroolsParserContext(internalWorkingMemory, features, graph, excludedFeatureClasses);
        RuleTracker ruleTracker = new RuleTracker(includedOutputRules, excludedOutputRules, includedOutputFields, excludedOutputFields, dpc, false);
        ruleTracker.setInputNumber(0);
        session.addEventListener(ruleTracker);
        KieBase kbase = session.getKieBase();

        CostCalculationRequest request = new CostCalculationRequest();
        request.setTrip(trip);
        request.setOrder(order);
        recursiveInsert(session, List.of(request));
        session.startProcess("P1");
        session.fireAllRules();
        session.dispose();
        return ruleTracker;
    }

    public RuleTracker runSession(Trip trip, Order order, Pair<Rule, String> outputTarget){
        KieSession session = kieContainer.newKieSession("ksession-rules");
        InternalWorkingMemory internalWorkingMemory = (InternalWorkingMemory) session;
        Map<String, Value> features = new HashMap<>();
        Graph<GraphNode, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);

        Set<String> excludedOutputRules = new HashSet<>();//Stream.of("Create Pallet").collect(Collectors.toSet());
        Set<String> includedOutputRules = new HashSet<>(); //Stream.of("CalculateTotal", "Cost").collect(Collectors.toSet());
        Set<String> excludedOutputFields = Stream.of("pallets", "order", "trip", "step", "distance", "transportType", "city", "Step").collect(Collectors.toSet());
        Set<String> includedOutputFields = new HashSet<>();//Stream.of().collect(Collectors.toSet());
        Set<String> excludedFeatureClasses = Stream.of("Pallet").collect(Collectors.toSet());

        DroolsParserContext dpc = new DroolsParserContext(internalWorkingMemory, features, graph, excludedFeatureClasses);
        RuleTracker ruleTracker = new RuleTracker(includedOutputRules, excludedOutputRules, includedOutputFields, excludedOutputFields, dpc, false);
        ruleTracker.setInputNumber(0);
        ruleTracker.setOutputTarget(outputTarget);
        session.addEventListener(ruleTracker);
        KieBase kbase = session.getKieBase();

        CostCalculationRequest request = new CostCalculationRequest();
        request.setTrip(trip);
        request.setOrder(order);
        recursiveInsert(session, List.of(request));
        session.startProcess("P1");
        session.fireAllRules();
        session.dispose();
        return ruleTracker;
    }

    @Test
    public void autowrapper() {
        Trip trip = getDefaultTrip();
        Order order = getSampleOrder();

        List<Feature> features = featureExtractor(List.of(trip, order));
        for (Feature f: features){
            System.out.println(f);
        }

//        RuleTracker ruleTracker = runSession(trip, order);
//
//        int outputIDX = 0;
//        List<Pair<Rule, String>> outputAccessors = new ArrayList<>();
//        for (Map.Entry<Rule, HashMap<String, Pair<Object, Object>>> entry : ruleTracker.differences.entrySet()){
//            System.out.println("=== "+entry.getKey()+ " ===");
//            for (Map.Entry<String, Pair<Object, Object>> subEntry : entry.getValue().entrySet()) {
//                System.out.println("\t"+outputIDX+": "+subEntry.getKey()+"= "+ subEntry.getValue().getSecond());
//                outputAccessors.add(new Pair<>(entry.getKey(), subEntry.getKey()));
//                outputIDX += 1;
//            }
//        }
//        int desiredOutputIDX = 19;
//        RuleTracker ruleTracker1 = runSession(trip, order, outputAccessors.get(desiredOutputIDX));
//        System.out.println(ruleTracker1.desiredOutput);
    }

    public List<Feature> featureExtractor(List<Object> inputs){
        List<Feature> fs = new ArrayList<>();
        for (Object o  : inputs) {
            String rawName = o.getClass().getName() + "_" + o.hashCode();
            HashMap<String, Object> objectContents = beanProperties(o, null, "", false);
            for (Map.Entry<String, Object> entry : objectContents.entrySet()){
                String featureName = rawName+"_"+entry.getKey();
                Object subObject = entry.getValue();
                if (subObject instanceof Number){
                    fs.add(new Feature(featureName, Type.NUMBER, new Value(((Number) subObject).doubleValue())));
                } else if (subObject instanceof Boolean){
                    fs.add(new Feature(featureName, Type.BOOLEAN, new Value(subObject)));
                } else if (subObject instanceof String){
                    fs.add(new Feature(featureName, Type.CATEGORICAL, new Value(subObject)));
                } else {
                    fs.add(new Feature(featureName, Type.UNDEFINED, new Value(subObject)));
                }
            }
        }
        return fs;
    }

    public void printGraph(Graph<GraphNode, DefaultEdge> graph){
        for (GraphNode g : graph.vertexSet()){
            g.finalized = true;
        }
        DOTExporter<GraphNode, DefaultEdge> exporter =
                new DOTExporter<>(v -> Integer.toString(v.hashCode()));
        exporter.setVertexAttributeProvider((v) -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(v.toString()));
            map.put("fillcolor", DefaultAttribute.createAttribute(v.getColor(false)));
            map.put("color", DefaultAttribute.createAttribute(v.getColor(true)));
            map.put("style", DefaultAttribute.createAttribute("filled"));
            map.put("fontcolor", DefaultAttribute.createAttribute(v.getColor(false).equals("white") ? "black": "white"));
            map.put("shape", DefaultAttribute.createAttribute(v.getShape()));
            return map;
        });
        Writer writer = new StringWriter();
        exporter.exportGraph(graph, writer);
        try {
            File newFile = new File("graph.dot");
            FileWriter fileWriter = new FileWriter(newFile);
            fileWriter.write(writer.toString());
            fileWriter.close();
            System.out.printf("Wrote %d-node graph to dotfile.%n", graph.vertexSet().size());
            Path currentRelativePath = Paths.get("");
            String currentAbsolutePath = currentRelativePath.toAbsolutePath().toString();
            System.out.printf("Rendering in %s...", currentAbsolutePath);
            ProcessBuilder pb = new ProcessBuilder("dot", "-Tpdf", "graph.dot", "-o", "graph.pdf");
            pb.inheritIO();
            pb.directory(new File(currentAbsolutePath));
            pb.start();
            System.out.println("[done]");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public double randBetween(double lower, double upper){
        return lower + (rn.nextDouble() * (upper-lower));
    }

    public List<Order> generateRandomOrders(int n, Order sample){
        HashSet<String> zeroDimProducts = new HashSet<>();
        zeroDimProducts.add("Sand");
        zeroDimProducts.add("Gravel");
        zeroDimProducts.add("Furniture");

        List<Order> orders = new ArrayList<>();
        for (int i=0; i<n; i++){
            Order o = new Order(Integer.toString(n));
            for (OrderLine orderLine : sample.getOrderLines()) {
                String name = orderLine.getProduct().getName();
                Product randomProduct = new Product(
                        name,
                        zeroDimProducts.contains(name) ? 0 : randBetween(.05, 2),
                        zeroDimProducts.contains(name) ? 0 : randBetween(.05, 1.2),
                        zeroDimProducts.contains(name) ? 0 : randBetween(.05, .8),
                        zeroDimProducts.contains(name) ? 0 : randBetween(.05, 1400),
                        orderLine.getProduct().getTransportType());
                if (orderLine.getProduct().getName().equals("Sand") || orderLine.getProduct().getName().equals("Gravel")) {
                    o.getOrderLines().add(new OrderLine(randBetween(1, 50000) , randomProduct));
                } else {
                    o.getOrderLines().add(new OrderLine(rn.nextInt(2000)+1, randomProduct));
                }
            }
            orders.add(o);
        }
        return orders;
    }

    public Trip getDefaultTrip(){
        City cityOfShangai = new City(City.ShangaiCityName);
        City cityOfRotterdam = new City(City.RotterdamCityName);
        City cityOfTournai = new City(City.TournaiCityName);
        City cityOfLille = new City(City.LilleCityName);
        Step step1 = new Step(cityOfShangai, cityOfRotterdam, 22000, Step.Ship_TransportType);
        Step step2 = new Step(cityOfRotterdam, cityOfTournai, 300, Step.train_TransportType);
        Step step3 = new Step(cityOfTournai, cityOfLille, 20, Step.truck_TransportType);
        Trip trip = new Trip("trip1");
        trip.getSteps().add(step1);
        trip.getSteps().add(step2);
        trip.getSteps().add(step3);
        return trip;
    }

    public Order getSampleOrder(){
        // build the order to explain
        Order order = new Order("toExplain");
        Product drillProduct = new Product("Drill", 0.2, 0.4, 0.3, 2, Product.transportType_pallet);
        Product screwDriverProduct = new Product("Screwdriver", 0.03, 0.02, 0.2, 0.2, Product.transportType_pallet);
        Product sandProduct = new Product("Sand", 0.0, 0.0, 0.0, 0.0, Product.transportType_bulkt);
        Product gravelProduct = new Product("Gravel", 0.0, 0.0, 0.0, 0.0, Product.transportType_bulkt);
        Product furnitureProduct = new Product("Furniture", 0.0, 0.0, 0.0, 0.0, Product.transportType_individual);

        order.getOrderLines().add(new OrderLine(1000, drillProduct));
        order.getOrderLines().add(new OrderLine(1000, screwDriverProduct));
        order.getOrderLines().add(new OrderLine(35000.0, sandProduct));
        order.getOrderLines().add(new OrderLine(14000.0, gravelProduct));
        order.getOrderLines().add(new OrderLine(500, furnitureProduct));
        return order;
    }
}
