import drools_integrators.DroolsWrapper;
import rulebases.buspass.Person;
import org.junit.Test;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.kie.kogito.explainability.local.counterfactual.CounterfactualConfig;
import org.kie.kogito.explainability.local.counterfactual.CounterfactualExplainer;
import org.kie.kogito.explainability.local.counterfactual.CounterfactualResult;
import org.kie.kogito.explainability.local.counterfactual.SolverConfigBuilder;
import org.kie.kogito.explainability.local.counterfactual.entities.CounterfactualEntity;
import org.kie.kogito.explainability.local.shap.ShapConfig;
import org.kie.kogito.explainability.local.shap.ShapKernelExplainer;
import org.kie.kogito.explainability.local.shap.ShapResults;
import org.kie.kogito.explainability.model.CounterfactualPrediction;
import org.kie.kogito.explainability.model.Feature;
import org.kie.kogito.explainability.model.FeatureFactory;
import org.kie.kogito.explainability.model.Output;
import org.kie.kogito.explainability.model.PerturbationContext;
import org.kie.kogito.explainability.model.Prediction;
import org.kie.kogito.explainability.model.PredictionInput;
import org.kie.kogito.explainability.model.PredictionOutput;
import org.kie.kogito.explainability.model.PredictionProvider;
import org.kie.kogito.explainability.model.SimplePrediction;
import org.kie.kogito.explainability.model.Type;
import org.kie.kogito.explainability.model.Value;
import org.kie.kogito.explainability.model.domain.NumericalFeatureDomain;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;


public class DroolsIntegrationBuspassTests {
    KieServices ks = KieServices.Factory.get();
    KieContainer kieContainer = ks.getKieClasspathContainer();

    // rui's counterfactual search helper function
    private CounterfactualResult runCounterfactualSearch(Long randomSeed, List<Output> goal,
                                                         List<Feature> features,
                                                         PredictionProvider model,
                                                         double goalThresold) throws InterruptedException, ExecutionException, TimeoutException {
        final TerminationConfig terminationConfig = new TerminationConfig().withScoreCalculationCountLimit(30_000L);
        final SolverConfig solverConfig = SolverConfigBuilder
                .builder().withTerminationConfig(terminationConfig).build();
        solverConfig.setRandomSeed(randomSeed);
        solverConfig.setEnvironmentMode(EnvironmentMode.REPRODUCIBLE);
        final CounterfactualConfig counterfactualConfig = new CounterfactualConfig();
        counterfactualConfig.withSolverConfig(solverConfig).withGoalThreshold(goalThresold);
        final CounterfactualExplainer explainer = new CounterfactualExplainer(counterfactualConfig);
        final PredictionInput input = new PredictionInput(features);
        PredictionOutput output = new PredictionOutput(goal);
        Prediction prediction =
                new CounterfactualPrediction(input,
                        output,
                        null,
                        UUID.randomUUID(),
                        600L);
        return explainer.explainAsync(prediction, model)
                .get(11L, TimeUnit.MINUTES);
    }


    // automatically wrap the drools model into a prediction provider + test counterfactual generation
    @Test
    public void testAutoWrapperCF() throws ExecutionException, InterruptedException, TimeoutException {
        // build the function to supply objects into the model
        Supplier<List<Object>> objectSupplier = () -> {
            Person p = new Person("Yoda", 10);
            return List.of(p);
        };

        // initialize the wrapper
        DroolsWrapper droolsWrapper = new DroolsWrapper(kieContainer,"BussPassGoodKS", objectSupplier);

        // setup Feature extraction
        droolsWrapper.displayFeatureCandidates();
        droolsWrapper.setFeatureExtractorFilters(List.of("(age)"));
        droolsWrapper.displayFeatureCandidates();
        for (Feature f: droolsWrapper.featureExtractor(objectSupplier.get()).keySet()) {
            droolsWrapper.addFeatureDomain(f.getName(), NumericalFeatureDomain.create(0., 100.));
        }
        PredictionInput samplePI = new PredictionInput(new ArrayList<>(droolsWrapper.featureExtractor(objectSupplier.get()).keySet()));
        droolsWrapper.generateOutputCandidates(true);
        droolsWrapper.selectOutputIndecesFromCandidates(List.of(2));

        // wrap model into predictionprovider
        PredictionProvider wrappedModel = droolsWrapper.wrap();
        System.out.println("== Original Output ==");
        wrappedModel.predictAsync(List.of(samplePI)).get().get(0).getOutputs().get(0).getValue();

        // run counterfactual
        List<Output> goal = new ArrayList<>();
        goal.add(new Output("rulebases.buspass.ChildBusPass_5", Type.CATEGORICAL, new Value("Not Created"), 0.0d));
        CounterfactualResult result = runCounterfactualSearch(0L, goal, samplePI.getFeatures(), wrappedModel, .1);
        System.out.println(result.getEntities().stream().map(CounterfactualEntity::asFeature).collect(Collectors.toList()));
        System.out.println(result.isValid());
        System.out.println(result.getOutput().get(0).getOutputs());
    }
}
