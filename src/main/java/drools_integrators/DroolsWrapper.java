package drools_integrators;

import org.apache.commons.math3.util.Pair;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.util.StringUtils;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.kie.api.definition.rule.Rule;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.kogito.explainability.model.Feature;
import org.kie.kogito.explainability.model.PredictionInput;
import org.kie.kogito.explainability.model.PredictionOutput;
import org.kie.kogito.explainability.model.PredictionProvider;
import org.kie.kogito.explainability.model.Type;
import org.kie.kogito.explainability.model.Value;
import org.kie.kogito.explainability.model.domain.EmptyFeatureDomain;
import org.kie.kogito.explainability.model.domain.FeatureDomain;

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
    private final KieContainer kieContainer;
    private List<String> featureWriterFilters;
    private List<Pattern> featureWriterRegexs;

    private Map<String, FeatureDomain> featureDomainMap = new HashMap<>();

    private Supplier<List<Object>> inputGenerator;

    private KieSession session;
    private InternalWorkingMemory internalWorkingMemory;
    private List<Pair<Rule, String>> outputAccessors;

    private List<Integer>outputIndeces;

    private final String sessionRules;

    private Set<String> includedOutputRules = new HashSet<>();
    private Set<String> excludedOutputRules= new HashSet<>();
    private Set<String> includedOutputFields= new HashSet<>();
    private Set<String> excludedOutputFields= new HashSet<>();


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


    public DroolsWrapper(KieContainer kieContainer, String sessionRules, Supplier<List<Object>> inputGenerator) {
        this.kieContainer = kieContainer;
        this.inputGenerator = inputGenerator;
        this.sessionRules = sessionRules;
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
                    f = new Feature(featureName, Type.BOOLEAN, new Value(subObject),false, featureDomain);
                } else if (subObject instanceof String){
                    f = new Feature(featureName, Type.CATEGORICAL, new Value(subObject),false, featureDomain);
                } else {
                    f = new Feature(featureName, Type.UNDEFINED, new Value(subObject),false, featureDomain);
                }
                fs.put(f, entry.getValue());
            }
        }
        return fs;
    }

    // generate the output candidate accessor dictionary, but do not print it out
    public void generateOutputCandidates() { generateOutputCandidates(false); }

    // generate the output candidate accessor dictionary, optionally print it out
    public void generateOutputCandidates(boolean display) {
        this.session = kieContainer.newKieSession(this.sessionRules);
        this.internalWorkingMemory = (InternalWorkingMemory) session;
        Map<String, Value> features = new HashMap<>();
        Graph<GraphNode, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
        ParserContext dpc = new ParserContext(internalWorkingMemory, features, graph, new HashSet<>());
        RuleFireListener ruleTracker = new RuleFireListener(includedOutputRules, excludedOutputRules, includedOutputFields, excludedOutputFields, dpc, false);
        session.addEventListener(ruleTracker);
        recursiveInsert(session, this.inputGenerator.get());
        session.startProcess("P1");
        session.fireAllRules();
        session.dispose();

        int outputIDX = 0;
        this.outputAccessors = new ArrayList<>();
        List<String> indeces = new ArrayList<>(List.of("Index"));
        List<String> fieldNames = new ArrayList<>(List.of("Field Name"));
        List<String> finalValues = new ArrayList<>(List.of("Final Value"));

        for (Map.Entry<Rule, Map<String, Pair<Object, Object>>> entry : ruleTracker.getDifferences().entrySet()) {
            for (Map.Entry<String, Pair<Object, Object>> subEntry : entry.getValue().entrySet()) {
                indeces.add(Integer.toString(outputIDX));
                fieldNames.add(subEntry.getKey());
                finalValues.add(subEntry.getValue().getSecond().toString());
                this.outputAccessors.add(new Pair<>(entry.getKey(), subEntry.getKey()));
                outputIDX += 1;
            }
        }


        if (display){
            int largestInt = indeces.stream().mapToInt(String::length).max().getAsInt()+1;
            int largestFN = fieldNames.stream().mapToInt(String::length).max().getAsInt()+1;
            int largestFV = finalValues.stream().mapToInt(String::length).max().getAsInt()+1;
            String fmtStr = String.format("%%%ds | %%%ds | %%%ds%n", largestInt, largestFN, largestFV);
            System.out.println("=== OUTPUT CANDIDATES "+
                    StringUtils.repeat("=",Math.max(0, largestInt+largestFN+largestFV - 15)));
            System.out.printf(fmtStr, indeces.get(0), fieldNames.get(0), finalValues.get(0));
            System.out.println(StringUtils.repeat("-",largestInt+largestFN+largestFV + 6));
            for (int i=1; i<indeces.size(); i++){
                System.out.printf(fmtStr, indeces.get(i), fieldNames.get(i), finalValues.get(i));
            }
            System.out.println(StringUtils.repeat("=", largestInt+largestFN+largestFV + 6));
        }
    }

    // select the output indececs of the output candidate accessor dictionary to select outputs from
    public void selectOutputIndecesFromCandidates(List<Integer> outputIndeces){
        this.outputIndeces = outputIndeces;
    }


    private PredictionOutput runSession(List<Object> droolsInputs){
        this.session = kieContainer.newKieSession(this.sessionRules);
        this.internalWorkingMemory = (InternalWorkingMemory) session;
        Map<String, Value> features = new HashMap<>();
        Graph<GraphNode, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);

        ParserContext dpc = new ParserContext(internalWorkingMemory, features, graph,  new HashSet<>());
        RuleFireListener ruleTracker = new RuleFireListener(includedOutputRules, excludedOutputRules, includedOutputFields, excludedOutputFields, dpc, false);
        ruleTracker.setInputNumber(0);
        ruleTracker.setOutputTargets(this.outputIndeces.stream()
                .map(this.outputAccessors::get)
                .collect(Collectors.toList()));
        session.addEventListener(ruleTracker);
        recursiveInsert(session, droolsInputs);
        session.startProcess("P1");
        session.fireAllRules();
        session.dispose();
        System.out.println("desiredout: "+ruleTracker.getDesiredOutputs().values()+"\n");
        return new PredictionOutput(new ArrayList<>(ruleTracker.getDesiredOutputs().values()));
    }

    public PredictionProvider wrap(){
        return inputs -> supplyAsync(() -> {
            List<Object> droolsInputs = this.inputGenerator.get();
            System.out.println("orig: " + inputs.get(0).getFeatures().stream().map(Feature::getValue).collect(Collectors.toList()));
            HashMap<Feature, FeatureWriter> featureWriterMap = featureExtractor(droolsInputs);
            List<PredictionOutput> outputs = new LinkedList<>();
            for (PredictionInput predictionInput : inputs){
                for (Feature f : predictionInput.getFeatures()){
                    for (Map.Entry<Feature, FeatureWriter> writerContainerEntry : featureWriterMap.entrySet()){
                        if (f.getName().equals(writerContainerEntry.getKey().getName())){
                            FeatureWriter featureWriter = writerContainerEntry.getValue();
                            try {
                                featureWriter.invoke(f.getValue().asString());
                            } catch (IllegalAccessException | InvocationTargetException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
                outputs.add(runSession(droolsInputs));
            }
            return outputs;
        });
    }
}
