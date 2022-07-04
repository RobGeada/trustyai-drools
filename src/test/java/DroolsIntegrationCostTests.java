import drools_integrators.DroolsWrapper;
import drools_integrators.ParserContext;
import drools_integrators.RuleFireListener;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.util.Pair;
import org.drools.core.common.InternalWorkingMemory;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.junit.Test;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieContainerSessionsPool;
import org.kie.api.runtime.KieSession;
import org.kie.kogito.explainability.local.counterfactual.CounterfactualConfig;
import org.kie.kogito.explainability.local.counterfactual.CounterfactualExplainer;
import org.kie.kogito.explainability.local.counterfactual.CounterfactualResult;
import org.kie.kogito.explainability.local.counterfactual.SolverConfigBuilder;
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
import org.kie.kogito.explainability.utils.MatrixUtilsExtensions;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import rulebases.cost.City;
import rulebases.cost.CostCalculationRequest;
import rulebases.cost.Order;
import rulebases.cost.OrderLine;
import rulebases.cost.Product;
import rulebases.cost.Step;
import rulebases.cost.Trip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.concurrent.Executors.newSingleThreadExecutor;


public class DroolsIntegrationCostTests {
    KieServices ks = KieServices.Factory.get();
    KieContainer kieContainer = ks.getKieClasspathContainer();


    // build the default trip input object
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

    // generate a sample order to run the model over
    public Order getDefaultOrder(){
        // build the order to explain
        Order order = new Order("toExplain");
        Product drillProduct = new Product("Drill", 0.2, 0.4, 0.3, 2, Product.transportType_pallet);
        Product screwDriverProduct = new Product("Screwdriver", 0.03, 0.02, 0.2, 0.2, Product.transportType_pallet);
        Product sandProduct = new Product("Sand", 0.0, 0.0, 0.0, 0.0, Product.transportType_bulkt);
        Product gravelProduct = new Product("Gravel", 0.0, 0.0, 0.0, 0.0, Product.transportType_bulkt);
        Product furnitureProduct = new Product("Furniture", 0.0, 0.0, 0.0, 0.0, Product.transportType_individual);

        order.getOrderLines().add(new OrderLine(1000, drillProduct));
        order.getOrderLines().add(new OrderLine(30000, screwDriverProduct));
        order.getOrderLines().add(new OrderLine(35000.0, sandProduct));
        order.getOrderLines().add(new OrderLine(14000.0, gravelProduct));
        order.getOrderLines().add(new OrderLine(500, furnitureProduct));
        return order;
    }

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

    public Pair<PredictionInput, PredictionProvider> getPIandWrappedModel(List<Integer> outputIndices){
        Supplier<List<Object>> objectSupplier = () -> {
            Trip trip = getDefaultTrip();
            Order order = getDefaultOrder();
            CostCalculationRequest request = new CostCalculationRequest();
            request.setTrip(trip);
            request.setOrder(order);
            return List.of(request);
        };

        // initialize the wrapper
        DroolsWrapper droolsWrapper = new DroolsWrapper(kieContainer,"CostRulesKS", objectSupplier, "P1");

        // setup Feature extraction
        droolsWrapper.setFeatureExtractorFilters(List.of("(orderLines\\[\\d+\\].weight)", "(orderLines\\[\\d+\\].numberItems)"));
        for (Feature f: droolsWrapper.featureExtractor(objectSupplier.get()).keySet()) {
            droolsWrapper.addFeatureDomain(f.getName(), NumericalFeatureDomain.create(0., ((Number) f.getValue().getUnderlyingObject()).doubleValue()));
        }
        droolsWrapper.displayFeatureCandidates();
        PredictionInput samplePI = new PredictionInput(new ArrayList<>(droolsWrapper.featureExtractor(objectSupplier.get()).keySet()));

        // setup Output extraction
        droolsWrapper.setExcludedOutputObjects(
                Stream.of("pallets", "LeftToDistribute", "cost.Product", "cost.OrderLine", "java.lang.Double", "costElements", "Pallet", "City", "Step", "org.drools.core.reteoo.InitialFactImpl", "java.util.ArrayList")
                        .collect(Collectors.toSet()));
        droolsWrapper.setExcludedOutputFields(
                Stream.of("pallets", "order", "trip", "step", "distance", "transportType", "city", "Step")
                        .collect(Collectors.toSet()));
        droolsWrapper.setIncludedOutputRules(List.of("CalculateTotal"));
        droolsWrapper.generateOutputCandidates(true);
        droolsWrapper.selectOutputIndicesFromCandidates(outputIndices);

        // wrap model into predictionprovider
        return new Pair<>(samplePI, droolsWrapper.wrap());
    }


    @Test
    public void singleRun() throws ExecutionException, InterruptedException {
        // build the function to supply objects into the model
        Pair<PredictionInput, PredictionProvider> predictionInputPredictionProviderPair = getPIandWrappedModel(List.of(3));
        PredictionInput samplePI = predictionInputPredictionProviderPair.getFirst();
        PredictionProvider wrappedModel = predictionInputPredictionProviderPair.getSecond();
        System.out.println("== Original Output ==");
        for (int i=0; i<2; i++) {
            wrappedModel.predictAsync(List.of(samplePI)).get().get(0).getOutputs().get(0).getValue();
        }
    }

    public void printCFResults(PredictionInput originalInput, CounterfactualResult result){
        System.out.println("= OLD =");
        System.out.println(originalInput.getFeatures().stream().map(o -> o.getValue().toString()).collect(Collectors.toList()));
        System.out.println("= NEW =");
        System.out.println(result.getEntities().stream().map(ce -> ce.asFeature().getValue().toString()).collect(Collectors.toList()));
        System.out.println("Valid? "+result.isValid());
        System.out.println("Found Output: " + result.getOutput().get(0).getOutputs().stream().map(o -> o.getValue().toString()).collect(Collectors.toList()));
    }

    // automatically wrap the drools model into a prediction provider + test counterfactual generation
    @Test
    public void totalCostCF() throws ExecutionException, InterruptedException, TimeoutException {
        // build the function to supply objects into the model
        Pair<PredictionInput, PredictionProvider> predictionInputPredictionProviderPair = getPIandWrappedModel(List.of(3));
        PredictionInput samplePI = predictionInputPredictionProviderPair.getFirst();
        PredictionProvider wrappedModel = predictionInputPredictionProviderPair.getSecond();

        // run counterfactual
        List<Output> goal = new ArrayList<>();
        goal.add(new Output("CalculateTotal: cost.CostCalculationRequest.totalCost", Type.NUMBER, new Value(1000000.), 0.0d));
        CounterfactualResult result = runCounterfactualSearch(0L, goal, samplePI.getFeatures(), wrappedModel, .1);
        printCFResults(samplePI, result);
    }

    @Test
    public void palletCountCF() throws ExecutionException, InterruptedException, TimeoutException {
        Pair<PredictionInput, PredictionProvider> predictionInputPredictionProviderPair = getPIandWrappedModel(List.of(4));
        PredictionInput samplePI = predictionInputPredictionProviderPair.getFirst();
        PredictionProvider wrappedModel = predictionInputPredictionProviderPair.getSecond();

        // run counterfactual
        List<Output> goal = new ArrayList<>();
        goal.add(new Output("CalculateTotal: cost.CostCalculationRequest.totalCost", Type.NUMBER, new Value(500.), 0.0d));
        CounterfactualResult result = runCounterfactualSearch(0L, goal, samplePI.getFeatures(), wrappedModel, 0);
        printCFResults(samplePI, result);
    }

    @Test
    public void handlingCostCF() throws ExecutionException, InterruptedException, TimeoutException {
        Pair<PredictionInput, PredictionProvider> predictionInputPredictionProviderPair = getPIandWrappedModel(List.of(0));
        PredictionInput samplePI = predictionInputPredictionProviderPair.getFirst();
        PredictionProvider wrappedModel = predictionInputPredictionProviderPair.getSecond();

        // run counterfactual
        List<Output> goal = new ArrayList<>();
        goal.add(new Output("CalculateTotal: cost.CostCalculationRequest.totalCost", Type.NUMBER, new Value(2500.), 0.0d));
        CounterfactualResult result = runCounterfactualSearch(0L, goal, samplePI.getFeatures(), wrappedModel, .1);
        printCFResults(samplePI, result);
    }

    @Test
    public void allCostsSHAP() throws ExecutionException, InterruptedException {
        Pair<PredictionInput, PredictionProvider> predictionInputPredictionProviderPair =
                getPIandWrappedModel(List.of(0, 2, 3, 5));
        PredictionInput samplePI = predictionInputPredictionProviderPair.getFirst();
        PredictionProvider wrappedModel = predictionInputPredictionProviderPair.getSecond();


        List<PredictionInput> background = new ArrayList<>();
        for (int i=0; i<1; i++) {
            List<Feature> backgroundFeatures = new ArrayList<>();
            for (int j = 0; j < samplePI.getFeatures().size(); j++) {
                Feature f = samplePI.getFeatures().get(j);
                backgroundFeatures.add(FeatureFactory.copyOf(f, new Value(1e-6)));
            }
            background.add(new PredictionInput(backgroundFeatures));
        }

        // wrap model into predictionprovider
        PredictionOutput samplePO = wrappedModel.predictAsync(List.of(samplePI)).get().get(0);
        Prediction samplePrediction = new SimplePrediction(samplePI, samplePO);


        // run SHAP
        ShapConfig config = ShapConfig.builder()
                .withLink(ShapConfig.LinkType.IDENTITY)
                .withPC(new PerturbationContext(new Random(0), 0))
                .withBackground(background)
                .withRegularizer(ShapConfig.RegularizerType.NONE)
                .withExecutor(newSingleThreadExecutor())
                .build();
        ShapKernelExplainer ske = new ShapKernelExplainer(config);
        ShapResults results = ske.explainAsync(samplePrediction, wrappedModel).get();
        System.out.println(results.toString());
    }


    @Test
    public void palletCountSHAP() throws ExecutionException, InterruptedException {
        Pair<PredictionInput, PredictionProvider> predictionInputPredictionProviderPair =
                getPIandWrappedModel(List.of(4));
        PredictionInput samplePI = predictionInputPredictionProviderPair.getFirst();
        PredictionProvider wrappedModel = predictionInputPredictionProviderPair.getSecond();

        List<PredictionInput> background = new ArrayList<>();
        for (int i=0; i<1; i++) {
            List<Feature> backgroundFeatures = new ArrayList<>();
            for (int j = 0; j < samplePI.getFeatures().size(); j++) {
                Feature f = samplePI.getFeatures().get(j);
                backgroundFeatures.add(FeatureFactory.copyOf(f, new Value(0)));
            }
            background.add(new PredictionInput(backgroundFeatures));
        }

        // wrap model into predictionprovider
        PredictionOutput samplePO = wrappedModel.predictAsync(List.of(samplePI)).get().get(0);
        Prediction samplePrediction = new SimplePrediction(samplePI, samplePO);

        // run SHAPoutputIndices
        ShapConfig config = ShapConfig.builder()
                .withLink(ShapConfig.LinkType.IDENTITY)
                .withPC(new PerturbationContext(new Random(0), 0))
                .withBackground(background)
                .withRegularizer(ShapConfig.RegularizerType.NONE)
                .withExecutor(newSingleThreadExecutor())
                .build();
        ShapKernelExplainer ske = new ShapKernelExplainer(config);
        ShapResults results = ske.explainAsync(samplePrediction, wrappedModel).get();
        System.out.println(results.toString());
    }
}
