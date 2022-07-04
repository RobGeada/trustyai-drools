package drools_integrators;

import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.util.StringUtils;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieContainerSessionsPool;
import org.kie.api.runtime.KieSession;
import org.kie.kogito.explainability.model.Feature;
import org.kie.kogito.explainability.model.PredictionInput;
import org.kie.kogito.explainability.model.PredictionOutput;
import org.kie.kogito.explainability.model.PredictionProvider;
import org.kie.kogito.explainability.model.Type;
import org.kie.kogito.explainability.model.Value;
import org.kie.kogito.explainability.model.domain.EmptyFeatureDomain;
import org.kie.kogito.explainability.model.domain.FeatureDomain;
import rulebases.cost.CostCalculationRequest;
import rulebases.cost.Pallet;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static drools_integrators.BeanReflectors.beanContainers;
import static drools_integrators.BeanReflectors.beanWriteProperties;
import static java.util.concurrent.CompletableFuture.supplyAsync;

public class DroolsWrapper {
    private final KieContainerSessionsPool pool;
    private List<String> featureWriterFilters;
    private List<Pattern> featureWriterRegexs;

    private final String bpmn;

    private Map<String, FeatureDomain> featureDomainMap = new HashMap<>();

    private Supplier<List<Object>> inputGenerator;
    private List<OutputAccessor> outputAccessors;

    private List<Integer> outputIndices;

    private final String sessionRules;

    private Set<String> includedOutputRules = new HashSet<>();
    private Set<String> excludedOutputRules= new HashSet<>();
    private Set<String> includedOutputObjects = new HashSet<>();
    private Set<String> excludedOutputObjects = new HashSet<>();
    private Set<String> includedOutputFields= new HashSet<>();
    private Set<String> excludedOutputFields= new HashSet<>();
    public int inputNumber = 0;
    public Graph<GraphNode, DefaultEdge> graph;
    public HashMap<Integer, GraphNode> graphNodeMap;

    public List<PredictionInput> allPis = new ArrayList<>();
    public List<PredictionOutput> allPos = new ArrayList<>();


    // set-based inclusion/exclusion setters
    public void setIncludedOutputRules(Set<String> includedOutputRules) {
        this.includedOutputRules = includedOutputRules;
    }

    public void setExcludedOutputRules(Set<String> excludedOutputRules) {
        this.excludedOutputRules = excludedOutputRules;
    }

    public void setIncludedOutputFields(Set<String> includedOutputFields) {
        this.includedOutputFields = includedOutputFields;
    }

    public void setExcludedOutputFields(Set<String> excludedOutputFields) {
        this.excludedOutputFields = excludedOutputFields;
    }

    public void setIncludedOutputObjects(Set<String> includedOutputObjects) {
        this.includedOutputObjects = includedOutputObjects;
    }

    public void setExcludedOutputObjects(Set<String> excludedOutputObjects) {
        this.excludedOutputObjects = excludedOutputObjects;
    }

    // list-based inclusion/exclusion setters
    public void setIncludedOutputRules(List<String> includedOutputRules) {
        this.includedOutputRules = new HashSet<>(includedOutputRules);
    }

    public void setExcludedOutputRules(List<String> excludedOutputRules) {
        this.excludedOutputRules = new HashSet<>(excludedOutputRules);
    }

    public void setIncludedOutputFields(List<String> includedOutputFields) {
        this.includedOutputFields = new HashSet<>(includedOutputFields);
    }

    public void setExcludedOutputFields(List<String> excludedOutputFields) {
        this.excludedOutputFields = new HashSet<>(excludedOutputFields);
    }

    public void setIncludedOutputObjects(List<String> includedOutputObjects) {
        this.includedOutputObjects = new HashSet<>(includedOutputObjects);
    }

    public void setExcludedOutputObjects(List<String> excludedOutputObjects) {
        this.excludedOutputObjects = new HashSet<>(excludedOutputObjects);
    }


    public DroolsWrapper(KieContainer kieContainer, String sessionRules, Supplier<List<Object>> inputGenerator) {
        this.inputGenerator = inputGenerator;
        this.sessionRules = sessionRules;
        this.pool = kieContainer.newKieSessionsPool(100);
        this.bpmn = null;
    }

    public DroolsWrapper(KieContainer kieContainer, String sessionRules, Supplier<List<Object>> inputGenerator, String bpmn) {
        this.inputGenerator = inputGenerator;
        this.sessionRules = sessionRules;
        this.pool = kieContainer.newKieSessionsPool(100);
        this.bpmn = bpmn;
    }

    public void setFeatureExtractorFilters(List<String> filters) {
        this.featureWriterFilters = filters;
        this.featureWriterRegexs = filters.stream().map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE)).collect(Collectors.toList());
    }

    public void addFeatureDomain(String string, FeatureDomain featureDomain) {
        this.featureDomainMap.put(string, featureDomain);
    }

    public void recursiveInsert(KieSession kieSession, List<Object> objectsToInsert){
        for (Object o : objectsToInsert){
            List<Object> subObjects = beanContainers(o, "", false, "");
            for (Object subObj : subObjects){
                kieSession.insert(subObj);
            }
        }
    }

    public HashMap<Feature, FeatureWriter> featureExtractor(List<Object> inputs) {
        HashMap<Feature, FeatureWriter> fs = new HashMap<>();
        for (int i=0; i<inputs.size(); i++) {
            Object input = inputs.get(i);
            String rawName = input.getClass().getName() + "_"+i;
            Map<String, FeatureWriter> writers = beanWriteProperties(input, false);
            for (Map.Entry<String, FeatureWriter> entry : writers.entrySet()){
                String featureName = rawName+"_"+entry.getKey();
                if (featureWriterFilters != null && this.featureWriterRegexs.stream().noneMatch(p -> p.matcher(featureName).find())){
                    continue;
                }
                Object subObject = entry.getValue().argument;
                Feature f;
                boolean constrained = true;
                FeatureDomain featureDomain = EmptyFeatureDomain.create();
                if (featureDomainMap.containsKey(featureName)){
                    constrained = false;
                    featureDomain = featureDomainMap.get(featureName);
                }
                if (subObject instanceof Number){
                    f = new Feature(featureName, Type.NUMBER, new Value(subObject), constrained, featureDomain);
                } else if (subObject instanceof Boolean){
                    f = new Feature(featureName, Type.BOOLEAN, new Value(subObject),constrained, featureDomain);
                } else if (subObject instanceof String || subObject instanceof Enum) {
                    f = new Feature(featureName, Type.CATEGORICAL, new Value(subObject), constrained, featureDomain);
                } else {
                    f = new Feature(featureName, Type.UNDEFINED, new Value(subObject),constrained, featureDomain);
                }
                fs.put(f, entry.getValue());
            }
        }
        return fs;
    }

    public void displayFeatureCandidates() {
        List<String> featureNames = new ArrayList<>(List.of("Feature"));
        List<String> featureValues = new ArrayList<>(List.of("Value"));
        Set<Feature> featureSet = featureExtractor(this.inputGenerator.get()).keySet();
        for (Feature f: featureSet){
            featureNames.add(f.getName());
            featureValues.add(f.getValue().toString());
        }

        int largestName = featureNames.stream().mapToInt(String::length).max().getAsInt()+1;
        int largestValue = featureValues.stream().mapToInt(String::length).max().getAsInt()+1;
        String fmtStr = String.format("%%%ds | %%%ds%n", largestName, largestValue);
        System.out.println("=== FEATURE CANDIDATES "+
                StringUtils.repeat("=",Math.max(0, largestName+largestValue - 15)));
        System.out.printf(fmtStr, featureNames.get(0), featureValues.get(0));
        System.out.println(StringUtils.repeat("-",largestName+largestValue+ 6));
        for (int i=1; i<featureNames.size(); i++){
            System.out.printf(fmtStr, featureNames.get(i), featureValues.get(i));
        }
        System.out.println(StringUtils.repeat("=",  largestName+largestValue + 6));
    }


    // generate the output candidate accessor dictionary, but do not print it out
    public void generateOutputCandidates() { generateOutputCandidates(false); }

    // generate the output candidate accessor dictionary, optionally print it out
    public void generateOutputCandidates(boolean display) {
        KieSession session = this.pool.newKieSession(this.sessionRules);
        InternalWorkingMemory internalWorkingMemory = (InternalWorkingMemory) session;
        Map<String, Value> features = new HashMap<>();
        Graph<GraphNode, DefaultEdge> outputGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
        ParserContext dpc = new ParserContext(internalWorkingMemory, features, outputGraph, new HashSet<>());
        RuleFireListener ruleFireListener = new RuleFireListener(
                includedOutputRules, excludedOutputRules,
                includedOutputFields, excludedOutputFields,
                includedOutputObjects, excludedOutputObjects,
                dpc, false);


        session.addEventListener(ruleFireListener);
        recursiveInsert(session, this.inputGenerator.get());
        if (this.bpmn != null) {
            session.startProcess(bpmn);
        }
        session.fireAllRules();
        session.dispose();

        int outputIDX = 0;
        this.outputAccessors = new ArrayList<>();
        List<String> indeces = new ArrayList<>(List.of("Index"));
        List<String> ruleNames = new ArrayList<>(List.of("Rule"));
        List<String> fieldNames = new ArrayList<>(List.of("Field Name"));
        List<String> finalValues = new ArrayList<>(List.of("Final Value"));

        for (Map.Entry<OutputAccessor, OutputCandidate> outputAccessorOutputCandidate : ruleFireListener.getDifferences().entrySet()) {
            indeces.add(Integer.toString(outputIDX));
            ruleNames.add(outputAccessorOutputCandidate.getKey().rule.getName());
            fieldNames.add(outputAccessorOutputCandidate.getKey().name);
            finalValues.add(outputAccessorOutputCandidate.getValue().after.toString());
            this.outputAccessors.add(outputAccessorOutputCandidate.getKey());
            outputIDX += 1;
        }

        if (display){
            int largestInt = indeces.stream().mapToInt(String::length).max().getAsInt()+1;
            int largestRule = ruleNames.stream().mapToInt(String::length).max().getAsInt()+1;
            int largestFN = fieldNames.stream().mapToInt(String::length).max().getAsInt()+1;
            int largestFV = finalValues.stream().mapToInt(String::length).max().getAsInt()+1;
            String fmtStr = String.format("%%%ds | %%%ds | %%%ds | %%%ds%n", largestInt, largestRule, largestFN, largestFV);
            System.out.println("=== OUTPUT CANDIDATES "+
                    StringUtils.repeat("=",Math.max(0, largestInt+largestRule+largestFN+largestFV - 15)));
            System.out.printf(fmtStr, indeces.get(0), ruleNames.get(0), fieldNames.get(0), finalValues.get(0));
            System.out.println(StringUtils.repeat("-",largestInt+largestRule+largestFN+largestFV + 6));
            for (int i=1; i<indeces.size(); i++){
                System.out.printf(fmtStr, indeces.get(i), ruleNames.get(i), fieldNames.get(i), finalValues.get(i));
            }
            System.out.println(StringUtils.repeat("=", largestInt+largestRule+largestFN+largestFV + 6));
        }
    }

    // select the output indececs of the output candidate accessor dictionary to select outputs from
    public void selectOutputIndicesFromCandidates(List<Integer> outputIndices){
        this.outputIndices = outputIndices;
    }

    public PredictionInput getSamplePredictionInput(){
        return new PredictionInput(new ArrayList<>(this.featureExtractor(this.inputGenerator.get()).keySet()));
    }

    private PredictionOutput runSession(List<Object> droolsInputs){
        KieSession session = this.pool.newKieSession(this.sessionRules);
        final InternalWorkingMemory internalWorkingMemory = (InternalWorkingMemory) session;
        Map<String, Value> features = new HashMap<>();
        if (this.inputNumber == 0) {
            this.graph = new DefaultDirectedGraph<>(DefaultEdge.class);
            this.graphNodeMap = new HashMap<>();
        }
        ParserContext dpc = new ParserContext(internalWorkingMemory, features, this.graph, this.graphNodeMap, new HashSet<>());
        RuleFireListener ruleFireListener = new RuleFireListener(
                includedOutputRules, excludedOutputRules,
                includedOutputFields, excludedOutputFields,
                includedOutputObjects, excludedOutputObjects,
                dpc, false);
        ruleFireListener.setInputNumber(this.inputNumber);
        ruleFireListener.setOutputTargets(this.outputIndices.stream()
                .map(this.outputAccessors::get)
                .collect(Collectors.toList()));
        session.addEventListener(ruleFireListener);
        recursiveInsert(session, droolsInputs);
        if (this.bpmn != null) {
            session.startProcess("P1");
        }
        session.fireAllRules();
        session.dispose();
//        for (Object o : droolsInputs){
//            if (o instanceof CostCalculationRequest){
//                CostCalculationRequest ccr = (CostCalculationRequest) o;
//                for (Pallet p : ccr.getPallets()){
//                    System.out.println(p);
//                }
//            }
//        }
        this.graphNodeMap = dpc.graphNodeMap;
        //System.out.println("Included Objects: "+ruleFireListener.getActualIncludedObjects());
        //System.out.println("Included Fields: "+ruleFireListener.getActualIncludedFields());
        //System.out.println(ruleFireListener.getDesiredOutputs());
        return new PredictionOutput(new ArrayList<>(ruleFireListener.getDesiredOutputs().values()));

    }

    public PredictionProvider wrap(){
        return inputs -> supplyAsync(() -> {
            List<PredictionOutput> outputs = new LinkedList<>();
            for (PredictionInput predictionInput : inputs){
                //allPis.add(predictionInput);
                List<Object> droolsInputs = this.inputGenerator.get();
                HashMap<Feature, FeatureWriter> featureWriterMap = featureExtractor(droolsInputs);
                for (Feature f : predictionInput.getFeatures()){
                    for (Map.Entry<Feature, FeatureWriter> writerContainerEntry : featureWriterMap.entrySet()){
                        if (f.getName().equals(writerContainerEntry.getKey().getName())){
                            FeatureWriter featureWriter = writerContainerEntry.getValue();
                            try {
                                featureWriter.invoke(f.getValue().getUnderlyingObject());
                            } catch (IllegalAccessException | InvocationTargetException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
                PredictionOutput po = runSession(droolsInputs);
//                allPos.add(po);
                outputs.add(po);
            }
//            for (int i=0; i<inputs.size(); i++){
//                String inputStr = "Input:  " + inputs.get(i).getFeatures().stream().map(f -> String.format("%s", f.getValue())).collect(Collectors.toList());
//                String outputStr = "Output: "+ outputs.get(i).getOutputs().stream().map(o -> o.getValue().toString()).collect(Collectors.toList());
//                System.out.println(inputStr + "\n" + outputStr);
//            }
            return outputs;
        });
    }
}
