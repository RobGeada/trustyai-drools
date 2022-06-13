import drools_integrators.BeanReflectors;
import drools_integrators.DroolsWrapper;
import jdk.jshell.spi.SPIResolutionException;
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
import org.kie.kogito.explainability.model.domain.CategoricalFeatureDomain;
import org.kie.kogito.explainability.model.domain.NumericalFeatureDomain;
import org.kie.kogito.explainability.model.domain.ObjectFeatureDomain;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import rulebases.cashflow.Account;
import rulebases.cashflow.AccountPeriod;
import rulebases.cashflow.CashFlow;
import rulebases.cashflow.CashFlowType;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static rulebases.cashflow.CashFlowMain.date;


public class DroolsIntegrationCashflowTests {
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
    public void testAutoWrapperSHAP() throws ExecutionException, InterruptedException {
        // build the function to supply objects into the model
        Supplier<List<Object>> objectSupplier = () -> {
            try {
                AccountPeriod acp = new AccountPeriod(date( "2013-01-01"), date( "2013-03-31"));
                Account ac = new Account(1, 0);
                CashFlow cf1 = new CashFlow(date( "2013-01-12"), 50, CashFlowType.CREDIT, 1 );
                CashFlow cf2 = new CashFlow(date( "2013-02-2"), 200, CashFlowType.DEBIT, 1 );
                CashFlow cf3 = new CashFlow(date( "2013-05-18"), 50, CashFlowType.CREDIT, 1 );
                CashFlow cf4 = new CashFlow(date( "2013-03-07"), 75, CashFlowType.CREDIT, 1 );
                return List.of(acp, ac, cf1, cf2, cf3, cf4);
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        };

        // initialize the wrapper
        DroolsWrapper droolsWrapper = new DroolsWrapper(kieContainer,"CashFlowKS", objectSupplier);

        // setup Feature extraction
        droolsWrapper.setFeatureExtractorFilters(List.of("(amount)"));
        droolsWrapper.displayFeatureCandidates();

        PredictionInput samplePI = new PredictionInput(new ArrayList<>(droolsWrapper.featureExtractor(objectSupplier.get()).keySet()));
        //droolsWrapper.generateOutputCandidates(true);
        droolsWrapper.setExcludedOutputObjects(List.of("AccountPeriod"));
        droolsWrapper.setExcludedOutputFields(List.of("date"));

        droolsWrapper.generateOutputCandidates(true);
        droolsWrapper.selectOutputIndecesFromCandidates(List.of(27));

        List<PredictionInput> background = new ArrayList<>();
        for (int i=0; i<1; i++) {
            List<Feature> backgroundFeatures = new ArrayList<>();
            for (Feature f : samplePI.getFeatures()) {
                backgroundFeatures.add(FeatureFactory.copyOf(f, new Value(0)));
            }
            background.add(new PredictionInput(backgroundFeatures));
        }

        // wrap model into predictionprovider
        PredictionProvider wrappedModel = droolsWrapper.wrap();
        System.out.println("== Original Output ==");
        PredictionOutput samplePO = wrappedModel.predictAsync(List.of(samplePI)).get().get(0);
        Prediction prediction = new SimplePrediction(samplePI, samplePO);

        // run counterfactual
        PerturbationContext pc = new PerturbationContext(new Random(), 0);
        ShapConfig sc = ShapConfig.builder()
                .withBackground(background)
                .withLink(ShapConfig.LinkType.IDENTITY)
                .withRegularizer(ShapConfig.RegularizerType.NONE)
                .withPC(pc)
                .build();
        ShapKernelExplainer ske = new ShapKernelExplainer(sc);
        ShapResults results = ske.explainAsync(prediction, wrappedModel).get();
        System.out.println(results.toString());

    }
}
