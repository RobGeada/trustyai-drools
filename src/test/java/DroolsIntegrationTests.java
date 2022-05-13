import cost.*;
import drools_integrators.DroolsWrapper;
import org.junit.Test;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.kie.kogito.explainability.local.counterfactual.CounterfactualConfig;
import org.kie.kogito.explainability.local.counterfactual.CounterfactualExplainer;
import org.kie.kogito.explainability.local.counterfactual.CounterfactualResult;
import org.kie.kogito.explainability.local.counterfactual.SolverConfigBuilder;
import org.kie.kogito.explainability.model.CounterfactualPrediction;
import org.kie.kogito.explainability.model.Feature;
import org.kie.kogito.explainability.model.Output;
import org.kie.kogito.explainability.model.Prediction;
import org.kie.kogito.explainability.model.PredictionInput;
import org.kie.kogito.explainability.model.PredictionOutput;
import org.kie.kogito.explainability.model.PredictionProvider;
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
import java.util.stream.Stream;


public class DroolsIntegrationTests {
    KieServices ks = KieServices.Factory.get();
    KieContainer kieContainer = ks.getKieClasspathContainer();

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
                        null);
        return explainer.explainAsync(prediction, model)
                .get(10L, TimeUnit.MINUTES);
    }
    @Test
    public void testAutoWrapper() throws ExecutionException, InterruptedException, TimeoutException {
        // build all objects inserted into the model
        Supplier<List<Object>> objectSupplier = () -> {
            Trip trip = getDefaultTrip();
            Order order = getDefaultOrder();
            CostCalculationRequest request = new CostCalculationRequest();
            request.setTrip(trip);
            request.setOrder(order);
            return List.of(request);
        };

        DroolsWrapper droolsWrapper = new DroolsWrapper(kieContainer,"ksession-rules", objectSupplier);
        droolsWrapper.setFeatureExtractorFilters(List.of("(orderLines\\[\\d+\\].weight)", "(orderLines\\[\\d+\\].numberItems)"));
        for (Feature f: droolsWrapper.featureExtractor(objectSupplier.get()).keySet()) {
            droolsWrapper.addFeatureDomain(f.getName(), NumericalFeatureDomain.create(0., ((Number) f.getValue().getUnderlyingObject()).doubleValue()));
        }
        PredictionInput samplePI = new PredictionInput(new ArrayList<>(droolsWrapper.featureExtractor(objectSupplier.get()).keySet()));
        droolsWrapper.setExcludedOutputFields(
                Stream.of("pallets", "order", "trip", "step", "distance", "transportType", "city", "Step")
                        .collect(Collectors.toSet()));
        droolsWrapper.generateOutputCandidates(false);
        droolsWrapper.selectOutputIndecesFromCandidates(List.of(19));
        PredictionProvider wrappedModel = droolsWrapper.wrap();

        List<Output> goal = new ArrayList<>();
        goal.add(new Output("CalculateTotal: cost.CostCalculationRequest.totalCost", Type.NUMBER, new Value(1000000.), 1.0));
        CounterfactualResult result = runCounterfactualSearch(0L, goal, samplePI.getFeatures(), wrappedModel, .01);
        System.out.println(result.getFeatures());
        System.out.println(result.isValid());
        System.out.println(result.getOutput().get(0).getOutputs());
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

    public Order getDefaultOrder(){
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
