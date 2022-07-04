import drools_integrators.DroolsWrapper;
import drools_integrators.OutputAccessor;
import drools_integrators.OutputCandidate;
import org.drools.core.util.StringUtils;
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
import org.kie.kogito.explainability.model.Value;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import rulebases.cost.City;
import rulebases.cost.Order;
import rulebases.cost.OrderLine;
import rulebases.cost.Product;
import rulebases.cost.Step;
import rulebases.cost.Trip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class TestUtils {
    public static void runCounterfactualSearch(DroolsWrapper wrapper, List<Output> goal) throws InterruptedException, ExecutionException, TimeoutException {
        final TerminationConfig terminationConfig = new TerminationConfig().withScoreCalculationCountLimit(30_000L);
        final SolverConfig solverConfig = SolverConfigBuilder
                .builder().withTerminationConfig(terminationConfig).build();
        solverConfig.setRandomSeed(0L);
        solverConfig.setEnvironmentMode(EnvironmentMode.REPRODUCIBLE);
        final CounterfactualConfig counterfactualConfig = new CounterfactualConfig();
        counterfactualConfig.withSolverConfig(solverConfig).withGoalThreshold(.01);
        final CounterfactualExplainer explainer = new CounterfactualExplainer(counterfactualConfig);


        List<Feature> features = wrapper.getSamplePredictionInput().getFeatures();

        final PredictionInput input = new PredictionInput(features);
        PredictionOutput output = new PredictionOutput(goal);
        Prediction prediction =
                new CounterfactualPrediction(input,
                        output,
                        null,
                        UUID.randomUUID(),
                        10L);
        System.out.println("Running Counterfactual Search, this will take a sec...");
        CounterfactualResult result = explainer.explainAsync(prediction, wrapper.wrap())
                .get(30L, TimeUnit.SECONDS);
        displayCounterFactual(result);
    }

    public static void runCounterfactualSearch(DroolsWrapper wrapper, List<Output> goal, long timer) throws InterruptedException, ExecutionException, TimeoutException {
        final TerminationConfig terminationConfig = new TerminationConfig().withScoreCalculationCountLimit(30_000L);
        final SolverConfig solverConfig = SolverConfigBuilder
                .builder().withTerminationConfig(terminationConfig).build();
        solverConfig.setRandomSeed(6L);
        solverConfig.setEnvironmentMode(EnvironmentMode.REPRODUCIBLE);
        final CounterfactualConfig counterfactualConfig = new CounterfactualConfig();
        counterfactualConfig.withSolverConfig(solverConfig).withGoalThreshold(0);
        final CounterfactualExplainer explainer = new CounterfactualExplainer(counterfactualConfig);


        List<Feature> features = wrapper.getSamplePredictionInput().getFeatures();

        final PredictionInput input = new PredictionInput(features);
        PredictionOutput output = new PredictionOutput(goal);
        Prediction prediction =
                new CounterfactualPrediction(input,
                        output,
                        null,
                        UUID.randomUUID(),
                        timer);
        System.out.println("Running Counterfactual Search, this will take a sec...");
        CounterfactualResult result = explainer.explainAsync(prediction, wrapper.wrap())
                .get(timer+10L, TimeUnit.SECONDS);
        displayCounterFactual(result);
    }

    public static void printFeatures(List<Feature> features) {
        List<String> featureNames = new ArrayList<>(List.of("Feature"));
        List<String> featureValues = new ArrayList<>(List.of("Value"));
        for (Feature f: features){
            featureNames.add(f.getName());
            featureValues.add(f.getValue().toString());
        }

        int largestName = featureNames.stream().mapToInt(String::length).max().getAsInt()+1;
        int largestValue = featureValues.stream().mapToInt(String::length).max().getAsInt()+1;
        String fmtStr = String.format("%%%ds | %%%ds%n", largestName, largestValue);
        System.out.println("=== COUNTERFACTUAL INPUT "+
                StringUtils.repeat("=",Math.max(0, largestName+largestValue - 15)));
        System.out.printf(fmtStr, featureNames.get(0), featureValues.get(0));
        System.out.println(StringUtils.repeat("-",largestName+largestValue+ 6));
        for (int i=1; i<featureNames.size(); i++){
            System.out.printf(fmtStr, featureNames.get(i), featureValues.get(i));
        }
        System.out.println(StringUtils.repeat("=",  largestName+largestValue + 6));
    }

    public static void printOutputs(List<Output> outputs) {
        List<String> featureNames = new ArrayList<>(List.of("Output"));
        List<String> featureValues = new ArrayList<>(List.of("Value"));
        for (Output o: outputs){
            featureNames.add(o.getName());
            featureValues.add(o.getValue().toString());
        }

        int largestName = featureNames.stream().mapToInt(String::length).max().getAsInt()+1;
        int largestValue = featureValues.stream().mapToInt(String::length).max().getAsInt()+1;
        String fmtStr = String.format("%%%ds | %%%ds%n", largestName, largestValue);
        System.out.println("=== COUNTERFACTUAL OUTPUT "+
                StringUtils.repeat("=",Math.max(0, largestName+largestValue - 15)));
        System.out.printf(fmtStr, featureNames.get(0), featureValues.get(0));
        System.out.println(StringUtils.repeat("-",largestName+largestValue+ 6));
        for (int i=1; i<featureNames.size(); i++){
            System.out.printf(fmtStr, featureNames.get(i), featureValues.get(i));
        }
        System.out.println(StringUtils.repeat("=",  largestName+largestValue + 6));
    }



    public static void displayCounterFactual(CounterfactualResult result){
        System.out.println("==== Counterfactual Search Results ====");
        printFeatures(result.getEntities().stream().map(CounterfactualEntity::asFeature).collect(Collectors.toList()));
        printOutputs(result.getOutput().get(0).getOutputs());
        System.out.println("Meets Criteria?       "+result.isValid());
    }

    public static Trip getDefaultTrip(){
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
    public static Order getDefaultOrder(){
        // build the order to explain
        Order order = new Order("toExplain");
        Product drillProduct = new Product("Drill", 0.2, 0.4, 0.3, 2, Product.transportType_pallet);
        Product screwDriverProduct = new Product("Screwdriver", 0.03, 0.02, 0.2, 0.2, Product.transportType_pallet);
        Product sandProduct = new Product("Sand", 0.0, 0.0, 0.0, 0.0, Product.transportType_bulkt);
        Product gravelProduct = new Product("Gravel", 0.0, 0.0, 0.0, 0.0, Product.transportType_bulkt);
        Product furnitureProduct = new Product("Furniture", 0.0, 0.0, 0.0, 0.0, Product.transportType_individual);

        order.getOrderLines().add(new OrderLine(1000, drillProduct));
        order.getOrderLines().add(new OrderLine(35000.0, sandProduct));
        order.getOrderLines().add(new OrderLine(14000.0, gravelProduct));
        order.getOrderLines().add(new OrderLine(500, furnitureProduct));
        return order;
    }

    public static void runSHAP(DroolsWrapper wrapper, double backgroundValue) throws ExecutionException, InterruptedException {
        PredictionProvider wrappedModel = wrapper.wrap();
        PredictionInput samplePI = wrapper.getSamplePredictionInput();
        PredictionOutput samplePO = wrappedModel.predictAsync(List.of(samplePI)).get().get(0);
        Prediction prediction = new SimplePrediction(samplePI, samplePO);
        List<PredictionInput> background = generateBackground(samplePI, backgroundValue);

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

    public static List<PredictionInput> generateBackground(PredictionInput samplePI, double backgroundValue){
        List<PredictionInput> background = new ArrayList<>();
        for (int i=0; i<1; i++) {
            List<Feature> backgroundFeatures = new ArrayList<>();
            for (int j = 0; j < samplePI.getFeatures().size(); j++) {
                Feature f = samplePI.getFeatures().get(j);
                backgroundFeatures.add(FeatureFactory.copyOf(f, new Value(backgroundValue)));
            }
            background.add(new PredictionInput(backgroundFeatures));
        }
        return background;
    }
}
