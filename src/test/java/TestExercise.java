import cost.*;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.util.Pair;
import org.drools.core.base.ClassFieldReader;
import org.drools.core.common.DefaultFactHandle;
import org.drools.core.common.InternalFactHandle;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.event.DefaultAgendaEventListener;
import org.drools.core.event.DefaultProcessEventListener;
import org.drools.core.event.DefaultRuleRuntimeEventListener;
import org.drools.core.impl.InternalKnowledgeBase;
import org.drools.core.reteoo.AccumulateNode;
import org.drools.core.reteoo.AlphaNode;
import org.drools.core.reteoo.EntryPointNode;
import org.drools.core.reteoo.JoinNode;
import org.drools.core.reteoo.LeftInputAdapterNode;
import org.drools.core.reteoo.NotNode;
import org.drools.core.reteoo.ObjectTypeNode;
import org.drools.core.reteoo.Rete;
import org.drools.core.reteoo.RightInputAdapterNode;
import org.drools.core.reteoo.RuleTerminalNode;
import org.drools.core.reteoo.Sink;
import org.drools.core.rule.EntryPointId;
import org.drools.core.spi.BetaNodeFieldConstraint;
import org.drools.core.spi.ObjectType;
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
import org.kie.api.event.process.ProcessNodeTriggeredEvent;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.BeforeMatchFiredEvent;
import org.kie.api.event.rule.ObjectInsertedEvent;
import org.kie.api.event.rule.ObjectUpdatedEvent;
import org.kie.api.event.rule.RuleFlowGroupActivatedEvent;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieContainerSessionsPool;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.kogito.explainability.local.lime.LimeConfig;
import org.kie.kogito.explainability.local.lime.LimeExplainer;
import org.kie.kogito.explainability.model.DataDistribution;
import org.kie.kogito.explainability.model.Feature;
import org.kie.kogito.explainability.model.FeatureFactory;
import org.kie.kogito.explainability.model.Output;
import org.kie.kogito.explainability.model.PerturbationContext;
import org.kie.kogito.explainability.model.PredictionInput;
import org.kie.kogito.explainability.model.PredictionInputsDataDistribution;
import org.kie.kogito.explainability.model.PredictionOutput;
import org.kie.kogito.explainability.model.PredictionProvider;
import org.kie.kogito.explainability.model.Saliency;
import org.kie.kogito.explainability.model.SimplePrediction;
import org.kie.kogito.explainability.model.Type;
import org.kie.kogito.explainability.model.Value;
import org.kie.kogito.explainability.utils.CompositeFeatureUtils;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.CompletableFuture.supplyAsync;

@SuppressWarnings("restriction")
public class TestExercise {
    Random rn = new Random(0);
    KieServices ks = KieServices.Factory.get();
    KieContainer kieContainer = ks.getKieClasspathContainer();
    KieContainerSessionsPool pool = kieContainer.newKieSessionsPool(100);

    private void insertIntoSession(KieSession sessionStatefull, CostCalculationRequest request) {
        sessionStatefull.insert(request);
        if (request.getOrder() != null) {
            sessionStatefull.insert(request.getOrder());
            for (OrderLine orderLine : request.getOrder().getOrderLines()) {
                sessionStatefull.insert(orderLine);
                sessionStatefull.insert(orderLine.getProduct());
            }
        }
        if (request.getTrip() != null) {
            sessionStatefull.insert(request.getTrip());
            for (Step step : request.getTrip().getSteps()) {
                sessionStatefull.insert(step);
                sessionStatefull.insert(step.getStepStart());
                sessionStatefull.insert(step.getStepEnd());
            }
        }
    }

    public PredictionInput predictionInputFromOrder(Order order){
        List<Feature> fs = new ArrayList<>();
        for (OrderLine o: order.getOrderLines()){
            List<Feature> compositeFeatures = new ArrayList<>();
            if (o.getWeight() != null) {
                compositeFeatures.add(FeatureFactory.newNumericalFeature("bulkWeight", o.getWeight()));
            } else {
                compositeFeatures.add(FeatureFactory.newNumericalFeature("number", o.getNumberItems()));
            }
            compositeFeatures.add(FeatureFactory.newNumericalFeature("height", o.getProduct().getHeight()));
            compositeFeatures.add(FeatureFactory.newNumericalFeature("width", o.getProduct().getWidth()));
            compositeFeatures.add(FeatureFactory.newNumericalFeature("depth", o.getProduct().getDepth()));
            compositeFeatures.add(FeatureFactory.newNumericalFeature("weight", o.getProduct().getWeight()));
            compositeFeatures.add(FeatureFactory.newNumericalFeature("transport_type", o.getProduct().getTransportType()));
            fs.add(FeatureFactory.newCompositeFeature(o.getProduct().getName(), compositeFeatures));
        }
        return new PredictionInput(fs);
    }

    public Order orderFromPredictionInput(PredictionInput pi){
        Order order = new Order("1");
        for (Feature f : pi.getFeatures()){
            HashMap<String, Double> orderDetails = new HashMap<>();
            List<Feature> compositeFeatures = (List<Feature>) f.getValue().getUnderlyingObject();
            for (Feature subf : compositeFeatures){
                try {
                    orderDetails.put(subf.getName(), (Double) subf.getValue().getUnderlyingObject());
                } catch(java.lang.ClassCastException e) {
                    orderDetails.put(subf.getName(), (Integer) subf.getValue().getUnderlyingObject()/1.);
                }
            }
            Product p = new Product(f.getName(), orderDetails.get("height"), orderDetails.get("width"), orderDetails.get("depth"), orderDetails.get("weight"), orderDetails.get("transport_type").intValue());
            if (orderDetails.containsKey("bulkWeight")){
                order.getOrderLines().add(new OrderLine(orderDetails.get("bulkWeight"), p));
            } else {
                order.getOrderLines().add(new OrderLine(orderDetails.get("number").intValue(), p));
            }
        }
        return order;
    }


    public String matrixPrettyPrint(RealMatrix m){
        HashMap<Integer, Integer> colSizes = new HashMap<>();
        for (int i=0; i<m.getRowDimension(); i++) {
            for (int j = 0; j < m.getColumnDimension(); j++) {
                int size = String.valueOf(Double.valueOf(m.getEntry(i, j)).intValue()).length();
                if (!colSizes.containsKey(j)) {
                    colSizes.put(j, size);
                } else if (colSizes.get(j) < size) {
                    colSizes.put(j, size);
                }
            }
        }

        StringBuilder stringBuilder = new StringBuilder();
        for (int i=0; i<m.getRowDimension(); i++){
            for (int j=0; j<m.getColumnDimension(); j++){
                String fmt = String.format("%%%d.2f", colSizes.get(j)+3);
                stringBuilder.append(String.format(fmt, m.getEntry(i,j)));
                if (j!=m.getColumnDimension()-1){
                    stringBuilder.append(", ");
                } else {
                    stringBuilder.append("\n");
                }
            }
        }
        return stringBuilder.toString();
    }


    int predictions = 0;

    public PredictionProvider wrapTrip(Trip trip) {
        return inputs -> supplyAsync(() -> {
            System.out.printf("Prediction %d (%d pis) %n", predictions, inputs.size());
            predictions += 1;
            List<PredictionOutput> predictionOutputs = new ArrayList<>();
            KieSession session = pool.newKieSession("ksession-rules");
            for (PredictionInput pi : inputs) {
                CostCalculationRequest request = new CostCalculationRequest();
                request.setTrip(trip);
                request.setOrder(orderFromPredictionInput(pi));
                this.insertIntoSession(session, request);

                // timeout in case of hanging processes
                ExecutorService executor = Executors.newCachedThreadPool();
                Callable<Object> task = () -> session.startProcess("P1");
                Future<Object> future = executor.submit(task);

                List<Output> outputs = new ArrayList<>();
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    System.out.println("The following PredictionInput timed out:");
                    System.out.println(pi);
                    outputs.add(new Output("Total Cost", Type.NUMBER, new Value(0.), 0.));
                } catch (ExecutionException | InterruptedException e) {
                    System.out.println("The following PredictionInput errored:");
                    System.out.println(pi);
                    outputs.add(new Output("Total Cost", Type.NUMBER, new Value(0.), 0.));
                    //throw new RuntimeException(e);
                }

                // if we haven't timed out:
                int i = session.fireAllRules();
                double taxCost = 0;
                double handlingCost = 0;
                double shippingCost = 0;
                for (CostElement ce : request.getCostElements()) {
                    if (ce instanceof TaxesCostElement) {
                        taxCost += ce.getAmount();
                    } else if (ce instanceof HandlingCostElement) {
                        handlingCost += ce.getAmount();
                    } else {
                        shippingCost += ce.getAmount();
                    }
                }
//                outputs.add(new Output("Tax Cost", Type.NUMBER, new Value(taxCost), 1.0));
//                outputs.add(new Output("Handling Cost", Type.NUMBER, new Value(handlingCost), 1.0));
//                outputs.add(new Output("Shipping Cost", Type.NUMBER, new Value(shippingCost), 1.0));
                outputs.add(new Output("Total Cost", Type.NUMBER, new Value(taxCost+handlingCost+shippingCost), 1.0));
                predictionOutputs.add(new PredictionOutput(outputs));
            }
//            RealMatrix piMatrix = MatrixUtilsExtensions.matrixFromPredictionInput(
//                    inputs.stream()
//                            .map(pi -> new PredictionInput(CompositeFeatureUtils.flattenFeatures(pi.getFeatures())))
//                            .collect(Collectors.toList()));
//            RealMatrix poMatrix = MatrixUtilsExtensions.matrixFromPredictionOutput(predictionOutputs);
//            System.out.println(matrixPrettyPrint(piMatrix));
//            System.out.println(matrixPrettyPrint(poMatrix));
            return predictionOutputs;
        });
    }

    class Node {
        String type;
        int id;

        public Node(String type, int id){
            this.type = type;
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Node node = (Node) o;
            return id == node.id && Objects.equals(type, node.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, id);
        }

        @Override
        public String toString(){
            return this.type;
        }

    }


    public void parseSinks(Sink[] sinks, List<FactHandle> relevantFactHandles,
                           InternalWorkingMemory internalWorkingMemory, Map<String, Value> features, Node parent, Graph<Node, DefaultEdge> graph, RuleTracker ruleTracker){
        for (Sink sink : sinks ){
            Node subNode;
            if (sink instanceof AlphaNode){
                AlphaNode node = (AlphaNode) sink;
                MVELConstraint mvelConstraint = (MVELConstraint) node.getConstraint();
                subNode = new Node("Alpha "+ mvelConstraint.getExpression(), node.getId());

                for (int i=0; i<relevantFactHandles.size(); i++){
                    FactHandle fh = relevantFactHandles.get(i);
                    Object fieldValue = mvelConstraint.getFieldExtractor().getValue(internalWorkingMemory, ((DefaultFactHandle) fh).getObject());
                    ClassFieldReader cfr = (ClassFieldReader) mvelConstraint.getFieldExtractor();
                    String featureName = String.format("%s.%s_#%d", cfr.getClassName(), cfr.getFieldName(), i);
                    features.put(featureName, new Value(fieldValue));
                    boolean conditionTrue = mvelConstraint.isAllowed((InternalFactHandle) fh, internalWorkingMemory);
                }
                graph.addVertex(subNode);
                parseSinks(node.getSinks(), relevantFactHandles, internalWorkingMemory, features, subNode, graph, ruleTracker);
            } else if (sink instanceof NotNode){
                NotNode node = (NotNode) sink;
                StringBuilder constraintNames = new StringBuilder();
                for (BetaNodeFieldConstraint bnfc : node.getConstraints()){
                    MVELConstraint mvelConstraint = (MVELConstraint) bnfc;
                    constraintNames.append(mvelConstraint.getExpression());
                };
                subNode = new Node("Not "+ constraintNames, node.getId());
                graph.addVertex(subNode);
                parseSinks(node.getSinks(), relevantFactHandles, internalWorkingMemory, features, subNode, graph, ruleTracker);
            } else if (sink instanceof JoinNode) {
                JoinNode node = (JoinNode) sink;
                subNode = new Node("Join", node.getId());
                graph.addVertex(subNode);
                parseSinks(node.getSinks(), relevantFactHandles, internalWorkingMemory, features, subNode, graph, ruleTracker);
            } else if (sink instanceof AccumulateNode) {
                AccumulateNode node = (AccumulateNode) sink;
                subNode = new Node("Accumulate", node.getId());
                graph.addVertex(subNode);
                parseSinks(node.getSinks(), relevantFactHandles, internalWorkingMemory, features, subNode,graph,  ruleTracker);
            } else if (sink instanceof LeftInputAdapterNode) {
                LeftInputAdapterNode node = (LeftInputAdapterNode) sink;
                subNode = new Node("LeftInput: "+ node.getObjectType().getClassName(), node.getId());
                graph.addVertex(subNode);
                for (int i=0; i<relevantFactHandles.size(); i++){
                    FactHandle fh = relevantFactHandles.get(i);
                }
                parseSinks(node.getSinks(), relevantFactHandles, internalWorkingMemory, features, subNode, graph, ruleTracker);
            }  else if (sink instanceof RightInputAdapterNode) {
                RightInputAdapterNode node = (RightInputAdapterNode) sink;
                subNode = new Node("RightInput: "+node.getLeftTupleSource().getObjectType().getClassName(), node.getId());
                graph.addVertex(subNode);
                for (int i=0; i<relevantFactHandles.size(); i++){
                    FactHandle fh = relevantFactHandles.get(i);
                }
                parseSinks(node.getSinks(), relevantFactHandles, internalWorkingMemory, features, subNode, graph, ruleTracker);
            } else if (sink instanceof RuleTerminalNode) {
                RuleTerminalNode node = (RuleTerminalNode) sink;
                subNode = new Node("Terminal: "+node.getRule().getName()+" caused: "+ruleTracker.differences.get(node.getRule()), node.getId());
                graph.addVertex(subNode);
            } else {
                subNode = new Node("Other: "+sink.getClass().getName(), sink.getId());
                graph.addVertex(subNode);
            }
            graph.addEdge(parent, subNode);
        }
    }



    public static HashMap<String, Object> beanProperties(final Object bean, Set<String> targets, Set<String> containers, String prefix, boolean verbose) {
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
        try {
            final PropertyDescriptor[] propertyDescriptors = Introspector.getBeanInfo(bean.getClass(), Object.class).getPropertyDescriptors();
            for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
                final Method readMethod = propertyDescriptor.getReadMethod();

                // if there's getters:
                if (readMethod != null) {
                    Object read = readMethod.invoke(bean, (Object[]) null);
                    name += "." + propertyDescriptor.getName();
                    boolean inTargets = targets.stream().anyMatch(propertyDescriptor.getName()::contains);
                    boolean inContainers = containers.stream().anyMatch(propertyDescriptor.getName()::contains);
                    if (verbose) {
                        System.out.printf("\t %s=%s, primitive? %b, %s in Targets? %b, %s in Containers? %b",
                                name, read.toString(),
                                (read instanceof Number || read instanceof String || read instanceof Boolean),
                                propertyDescriptor.getName(), inTargets,
                                propertyDescriptor.getName(), inContainers);
                    }

                    // if the get'ted object is a 'base' type:
                    if (read instanceof Number || read instanceof String || read instanceof Boolean) {
                        result.put(name, read);
                        if (verbose){System.out.println("...adding to result");}
                    } else if (read instanceof Iterable) { //is is an iterable object?
                        int i = 0;
                        if (verbose){System.out.printf("%n=== recursing %s ======================%n", name);}
                        for (Object o : (Iterable<?>) read) {
                            beanProperties(
                                    o,
                                    targets,
                                    containers,
                                    name + "[" + i + "]",
                                    verbose)
                                    .forEach(result::putIfAbsent);
                            i++;
                        }
                        if (verbose){System.out.println("=== end recursion ==================================\n");}
                    } else { // if the object is not base or iterable, but is a specified container:
                        if (verbose){System.out.println("...unpacking ======================");}
                        beanProperties(
                                read,
                                targets,
                                containers,
                                name + "." + propertyDescriptor.getName(),
                                verbose)
                                .forEach(result::putIfAbsent);
                        if (verbose){System.out.println("=== end unpack ==================================");}
                    }
                }
            }
        } catch (Exception ex) {
            // ignore
        }
        return result;
    }


    class RuleTracker extends DefaultAgendaEventListener {
        HashMap<String, HashMap<String, Object>> beforeHashes = new HashMap<>();
        HashMap<String, HashMap<String, Object>> afterHashes = new HashMap<>();
        HashMap<Rule, Object> differences = new HashMap<>();
        Set<String> outputTargets;
        Set<String> outputContainers;
        Set<String> excludedRules;

        public RuleTracker(Set<String> outputTargets, Set<String> outputContainers, Set<String> excludedRules) {
            this.outputTargets = outputTargets;
            this.outputContainers = outputContainers;
            this.excludedRules = excludedRules;
        }

        @Override
        public void afterMatchFired(AfterMatchFiredEvent event) {
            super.afterMatchFired(event);
            if (excludedRules.stream().noneMatch(event.getMatch().getRule().getName()::contains)) {
                boolean verbose = event.getMatch().getRule().getName().equals("CalculateTotal");
                List<InternalFactHandle> eventFactHandles = (List<InternalFactHandle>) event.getMatch().getFactHandles();
                for (InternalFactHandle fh : eventFactHandles) {
                    afterHashes.put(fh.getObject().getClass().getName() + "_" + fh.hashCode(), beanProperties(fh.getObject(), outputTargets, this.outputContainers, "", false));
                }
                if (true || verbose){
                    System.out.println(afterHashes);
                    System.out.println();
                }


                Set<String> keySets = new HashSet<>(afterHashes.keySet());
                keySets.addAll(beforeHashes.keySet());

                for (String key : new ArrayList<>(keySets)) {
                    if (!beforeHashes.containsKey(key)) {
                        this.differences.put(event.getMatch().getRule(), afterHashes.get(key));
                    }
                    if (!afterHashes.containsKey(key)) {
                        this.differences.put(event.getMatch().getRule(), beforeHashes.get(key));
                    }
                    if (beforeHashes.containsKey(key) && afterHashes.containsKey(key)) {
                        HashMap<String, Object> afterValues = afterHashes.get(key);
                        HashMap<String, Object> beforeValues = beforeHashes.get(key);

                        Set<String> objectKeySets = new HashSet<>(afterValues.keySet());
                        objectKeySets.addAll(new ArrayList<>(beforeValues.keySet()));

                        for (String objectKey : objectKeySets) {
                            if (!beforeValues.containsKey(objectKey)) {
                                this.differences.put(event.getMatch().getRule(), afterValues.get(objectKey));
                            }
                            if (!afterValues.containsKey(objectKey)) {
                                this.differences.put(event.getMatch().getRule(), beforeValues.get(objectKey));
                                if (beforeValues.containsKey(objectKey) && afterValues.containsKey(objectKey)) {
                                    if (!beforeValues.get(objectKey).equals(afterValues.get(objectKey))) {
                                        this.differences.put(event.getMatch().getRule(), "before: " + beforeValues.get(objectKey) + ", after: " + afterValues.get(objectKey));
                                    }
                                }
                            }
                            beforeHashes.remove(key);
                            afterHashes.remove(key);
                        }
                    }
                }
            }
        }
    }


    @Test
    public void explore() {
        KieSession session = kieContainer.newKieSession("ksession-rules");

        Set<String> outputTargets = new HashSet<>();
        outputTargets.add("amount");
        outputTargets.add("Total");
        Set<String> outputContainers = new HashSet<>();
        outputContainers.add("costElements");
        outputContainers.add("CostElement");

        Set<String> excludedRules = new HashSet<>();
        excludedRules.add("Create Pallet");

        RuleTracker ruleTracker = new RuleTracker(outputTargets, outputContainers, excludedRules);
        session.addEventListener(ruleTracker);
        //session.addEventListener(new DefaultRuleRuntimeEventListener());
        KieBase kbase = session.getKieBase();

        Pair<Trip, Order> tripOrderPair = getSampleOrder();
        CostCalculationRequest request = new CostCalculationRequest();
        request.setTrip(tripOrderPair.getFirst());
        request.setOrder(tripOrderPair.getSecond());

        this.insertIntoSession(session, request);
        session.startProcess("P1");
        session.fireAllRules();
        System.out.println(ruleTracker.differences);


        List<FactHandle> factHandles = new ArrayList<>(session.getFactHandles());
        Rete rete = ((InternalKnowledgeBase) kbase).getRete();
        InternalWorkingMemory internalWorkingMemory = (InternalWorkingMemory) session;


        Map<String, Value> features = new HashMap<>();
        Map<String, Double> featureWeighting = new HashMap<>();

        Graph<Node, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);

        for (Map.Entry<EntryPointId, EntryPointNode> entryPointSet : rete.getEntryPointNodes().entrySet()){
            EntryPointNode epn = entryPointSet.getValue();
            Node entrypoint = new Node("Entrypoint", epn.getId());
            graph.addVertex(entrypoint);

            for (Map.Entry<ObjectType, ObjectTypeNode> objectSet : epn.getObjectTypeNodes().entrySet()){
                ObjectTypeNode objectTypeNode = objectSet.getValue();
                Node objectNode = new Node("Object " + objectTypeNode.getObjectType().getClassName(), objectTypeNode.getId());
                List<FactHandle> relevantFactHandles = new ArrayList<>();
                for (FactHandle fh : factHandles){
                    DefaultFactHandle dfh = (DefaultFactHandle) fh;
                    if (Objects.equals(dfh.getObjectClassName(), objectTypeNode.getObjectType().getClassName())){
                        relevantFactHandles.add(fh);
                    }
                }
                graph.addVertex(objectNode);
                parseSinks(objectTypeNode.getSinks(), relevantFactHandles, internalWorkingMemory, features, objectNode, graph, ruleTracker);
                graph.addEdge(entrypoint, objectNode);
            }
        }

        //printGraph(graph);
        System.out.println(features);
    }

    public void printGraph(Graph<Node, DefaultEdge> graph){
        DOTExporter<Node, DefaultEdge> exporter =
                new DOTExporter<>(v -> Integer.toString(v.id));
        exporter.setVertexAttributeProvider((v) -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(v.toString()));
            return map;
        });
        Writer writer = new StringWriter();
        exporter.exportGraph(graph, writer);
        System.out.println(writer.toString());
    }


    public PredictionProvider flattenedModel(PredictionProvider originalModel, List<Feature> originalFeatures){
        return inputs -> {
            List<PredictionInput> unflattened = new ArrayList<>();
            for (PredictionInput pi : inputs){
                unflattened.add(new PredictionInput(CompositeFeatureUtils.unflattenFeatures(pi.getFeatures(), originalFeatures)));
            }
            return originalModel.predictAsync(unflattened);
        };
    }

    public double randBetween(double lower, double upper){
        return lower + (rn.nextDouble() * (upper-lower));
    }

    public List<PredictionInput> generateRandomOrders(int n, Order sample){
        HashSet<String> zeroDimProducts = new HashSet<>();
        zeroDimProducts.add("Sand");
        zeroDimProducts.add("Gravel");
        zeroDimProducts.add("Furniture");

        List<PredictionInput> pis = new ArrayList<>();
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
                    // o.getOrderLines().add(new OrderLine(randBetween(1, 2) , randomProduct));
                    o.getOrderLines().add(new OrderLine(0., randomProduct));
                } else {
                    o.getOrderLines().add(new OrderLine(0, randomProduct));
                    //o.getOrderLines().add(new OrderLine(rn.nextInt(1)+1, randomProduct));
                }
            }
            pis.add(predictionInputFromOrder(o));
        }
        return pis;
    }

    public List<PredictionInput> generateNullOrders(Order sample){
        HashSet<String> zeroDimProducts = new HashSet<>();
        zeroDimProducts.add("Sand");
        zeroDimProducts.add("Gravel");
        zeroDimProducts.add("Furniture");

        List<PredictionInput> pis = new ArrayList<>();
        Order o = new Order("0");
        for (OrderLine orderLine : sample.getOrderLines()) {
            String name = orderLine.getProduct().getName();
            Product randomProduct = new Product(
                    name, 1, 1, 1, 1, orderLine.getProduct().getTransportType());
            if (orderLine.getProduct().getName().equals("Sand") || orderLine.getProduct().getName().equals("Gravel")) {
                o.getOrderLines().add(new OrderLine(1., randomProduct));
            } else {
                o.getOrderLines().add(new OrderLine(1, randomProduct));
            }
        }
        pis.add(predictionInputFromOrder(o));
        return pis;
    }

    public Pair<Trip, Order> getSampleOrder(){
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

        // wrap into model


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

        return new Pair<>(trip, order);
    }


    @Test
    public void testTrustyAISHAP() throws ExecutionException, InterruptedException {
        System.setProperty("org.jbpm.rule.task.firelimit", "100000000");

        Pair<Trip, Order> tripOrderPair = getSampleOrder();
        PredictionProvider model = wrapTrip(tripOrderPair.getFirst());

        // convert to Prediction
        PredictionInput toExplainPI = predictionInputFromOrder(tripOrderPair.getSecond());

        // generate random background
        List<PredictionInput> randomOrders = generateRandomOrders(100, tripOrderPair.getSecond());
        model.predictAsync(randomOrders).get();
//
//
//        List<PredictionInput> flattenedOrders = randomOrders.stream().map(pi -> new PredictionInput(CompositeFeatureUtils.flattenFeatures(pi.getFeatures()))).collect(Collectors.toList());
//
//        // flatten model
//        PredictionProvider shapModel = flattenedModel(model, toExplainPI.getFeatures());
//        PredictionOutput toExplainPO = model.predictAsync(List.of(toExplainPI)).get().get(0);
//        SimplePrediction prediction = new SimplePrediction(new PredictionInput(CompositeFeatureUtils.flattenFeatures(toExplainPI.getFeatures())), toExplainPO);
//
//
//        PerturbationContext pc = new PerturbationContext(rn, 0);
//        ShapConfig skConfig = ShapConfig.builder()
//                .withBackground(flattenedOrders)
//                .withPC(pc)
//                .withLink(ShapConfig.LinkType.IDENTITY)
//                .withRegularizer(ShapConfig.RegularizerType.NONE)
//                .withBatchSize(1)
//                .build();
//        ShapKernelExplainer ske = new ShapKernelExplainer(skConfig);
//        ShapResults explanation = ske.explainAsync(prediction, shapModel).get();
//        System.out.println(explanation.toString());
    }

    @Test
    public void testTrustyAILIME() throws ExecutionException, InterruptedException {
        System.setProperty("org.jbpm.rule.task.firelimit", "100000000");

        // define the fixed parameters of the model
        City cityOfShangai = new City(City.ShangaiCityName);
        City cityOfRotterdam = new City(City.RotterdamCityName);
        City cityOfTournai = new City(City.TournaiCityName);
        City cityOfLille = new City(City.LilleCityName);
        Step step1 = new Step(cityOfShangai, cityOfRotterdam, 22000, Step.Ship_TransportType);
        Step step2 = new Step(cityOfRotterdam, cityOfTournai, 300, Step.train_TransportType);
        Step step3 = new Step(cityOfTournai, cityOfLille, 20, Step.truck_TransportType);
        Trip myTrip = new Trip("trip1");
        myTrip.getSteps().add(step1);
        myTrip.getSteps().add(step2);
        myTrip.getSteps().add(step3);

        // wrap into model
        PredictionProvider model = wrapTrip(myTrip);

        // build the order to explain
        Order orderToExplain = new Order("toExplain");
        Product drillProduct = new Product("Drill", 0.2, 0.4, 0.3, 2, Product.transportType_pallet);
        Product screwDriverProduct = new Product("Screwdriver", 0.03, 0.02, 0.2, 0.2, Product.transportType_pallet);
        Product sandProduct = new Product("Sand", 0.0, 0.0, 0.0, 0.0, Product.transportType_bulkt);
        Product gravelProduct = new Product("Gravel", 0.0, 0.0, 0.0, 0.0, Product.transportType_bulkt);
        Product furnitureProduct = new Product("Furniture", 0.0, 0.0, 0.0, 0.0, Product.transportType_individual);

        orderToExplain.getOrderLines().add(new OrderLine(1000, drillProduct));
        orderToExplain.getOrderLines().add(new OrderLine(1000, screwDriverProduct));
        orderToExplain.getOrderLines().add(new OrderLine(35000.0, sandProduct));
        orderToExplain.getOrderLines().add(new OrderLine(14000.0, gravelProduct));
        orderToExplain.getOrderLines().add(new OrderLine(500, furnitureProduct));

        // convert to Prediction
        PredictionInput toExplainPI = predictionInputFromOrder(orderToExplain);
        PredictionOutput toExplainPO = model.predictAsync(List.of(toExplainPI)).get().get(0);
        SimplePrediction prediction = new SimplePrediction(toExplainPI, toExplainPO);

        List<PredictionInput> randomOrders = generateRandomOrders(10000, orderToExplain);
        DataDistribution dataDistribution = new PredictionInputsDataDistribution(randomOrders, rn);

        // LIME explanation
        PerturbationContext pc = new PerturbationContext(rn, 0);
        LimeConfig config = new LimeConfig()
                .withPerturbationContext(pc)
                .withSamples(100)
                .withDataDistribution(dataDistribution)
                .withAdaptiveVariance(true);
        LimeExplainer le = new LimeExplainer(config);
        Map<String, Saliency> explanation = le.explainAsync(prediction, model).get();
        for (Map.Entry<String, Saliency> item : explanation.entrySet()){System.out.println(item.getKey());
            System.out.println(item.getValue());
        }
    }
}
