import drools_integrators.DroolsWrapper;
import org.junit.Test;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.kie.kogito.explainability.local.counterfactual.CounterfactualResult;
import org.kie.kogito.explainability.model.Feature;
import org.kie.kogito.explainability.model.Output;
import org.kie.kogito.explainability.model.Type;
import org.kie.kogito.explainability.model.Value;
import org.kie.kogito.explainability.model.domain.NumericalFeatureDomain;
import rulebases.buspass.Person;
import rulebases.cashflow.Account;
import rulebases.cashflow.AccountPeriod;
import rulebases.cashflow.CashFlow;
import rulebases.cashflow.CashFlowType;
import rulebases.cost.CostCalculationRequest;
import rulebases.cost.Order;
import rulebases.cost.Trip;

import java.text.ParseException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static rulebases.cashflow.CashFlowMain.date;

public class LiveDemo {
    KieServices ks = KieServices.Factory.get();
    KieContainer kieContainer = ks.getKieClasspathContainer();

    @Test
    public void buspassCF() throws ExecutionException, InterruptedException, TimeoutException {
        Supplier<List<Object>> objectSupplier = () -> {
            Person p = new Person("Yoda", 10);
            return List.of(p);
        };

        DroolsWrapper droolsWrapper = new DroolsWrapper(
                kieContainer, "BussPassGoodKS", objectSupplier);

        droolsWrapper.setFeatureExtractorFilters(List.of("(age)"));
        droolsWrapper.displayFeatureCandidates();

        for (Feature f : droolsWrapper.featureExtractor(objectSupplier.get()).keySet()) {
            droolsWrapper.addFeatureDomain(
                    f.getName(),
                    NumericalFeatureDomain.create(0., 100.));
        }

        droolsWrapper.generateOutputCandidates(true);
        droolsWrapper.selectOutputIndicesFromCandidates(List.of(10));

        List<Output> goal = List.of(
                new Output(
                        "rulebases.buspass.ChildBusPass_5",
                        Type.CATEGORICAL,
                        new Value("Not Created"),
                        0.0d)
        );
        TestUtils.runCounterfactualSearch(droolsWrapper, goal);


    }

    @Test
    public void cashflowSHAPExplanation() throws ExecutionException, InterruptedException {
        Supplier<List<Object>> objectSupplier = () -> {
            try {
                AccountPeriod acp = new AccountPeriod(date("2022-01-01"), date("2022-03-31"));
                Account ac = new Account(1, 0);
                CashFlow cf1 = new CashFlow(date("2022-01-12"), 63, CashFlowType.CREDIT, 1);
                CashFlow cf2 = new CashFlow(date("2022-02-2"), 200, CashFlowType.DEBIT, 1);
                CashFlow cf3 = new CashFlow(date("2022-05-18"), 50, CashFlowType.CREDIT, 1);
                CashFlow cf4 = new CashFlow(date("2022-03-07"), 75, CashFlowType.CREDIT, 1);
                return List.of(acp, ac, cf1, cf2, cf3, cf4);
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        };

        DroolsWrapper droolsWrapper = new DroolsWrapper(
                kieContainer, "CashFlowKS", objectSupplier);

        droolsWrapper.setIncludedOutputRules(List.of("Print balance for AccountPeriod"));
        droolsWrapper.generateOutputCandidates(false);
        droolsWrapper.selectOutputIndicesFromCandidates(List.of(2));

        droolsWrapper.setFeatureExtractorFilters(List.of("(amount)"));
        droolsWrapper.displayFeatureCandidates();

        TestUtils.runSHAP(droolsWrapper, 0);
    }

    @Test
    public void allCostsSHAP() throws ExecutionException, InterruptedException, TimeoutException {
        Supplier<List<Object>> objectSupplier = () -> {
            Trip trip = TestUtils.getDefaultTrip();
            Order order = TestUtils.getDefaultOrder();
            CostCalculationRequest request = new CostCalculationRequest();
            request.setTrip(trip);
            request.setOrder(order);
            return List.of(request);
        };

        DroolsWrapper droolsWrapper = new DroolsWrapper(kieContainer,"CostRulesKS", objectSupplier, "P1");
        droolsWrapper.setFeatureExtractorFilters(List.of("(orderLines\\[\\d+\\].weight)", "(orderLines\\[\\d+\\].numberItems)"));

        droolsWrapper.displayFeatureCandidates();

        droolsWrapper.setIncludedOutputRules(List.of("CalculateTotal"));
        droolsWrapper.setExcludedOutputObjects(
                Stream.of("pallets", "LeftToDistribute", "cost.Product", "cost.OrderLine", "java.lang.Double", "costElements", "Pallet", "City", "Step", "org.drools.core.reteoo.InitialFactImpl", "java.util.ArrayList")
                        .collect(Collectors.toSet()));
        droolsWrapper.setExcludedOutputFields(
                Stream.of("pallets", "order", "trip", "step", "distance", "transportType", "city", "Step")
                        .collect(Collectors.toSet()));


        droolsWrapper.generateOutputCandidates(true);
        droolsWrapper.selectOutputIndicesFromCandidates(List.of(4));

        List<Output> goal = List.of(
                new Output(
                        "rulebases.cost.CostCalculationRequest.numPallets",
                        Type.NUMBER,
                        new Value(500), 0.0d)
        );
        TestUtils.runCounterfactualSearch(droolsWrapper, goal, 30L);

    }


    @Test
    public void palletCountSHAP() throws ExecutionException, InterruptedException, TimeoutException {
        Supplier<List<Object>> objectSupplier = () -> {
            Trip trip = TestUtils.getDefaultTrip();
            Order order = TestUtils.getDefaultOrder();
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

        // setup Output extraction
        droolsWrapper.setExcludedOutputObjects(
                Stream.of("pallets", "LeftToDistribute", "cost.Product", "cost.OrderLine", "java.lang.Double", "costElements", "Pallet", "City", "Step", "org.drools.core.reteoo.InitialFactImpl", "java.util.ArrayList")
                        .collect(Collectors.toSet()));
        droolsWrapper.setExcludedOutputFields(
                Stream.of("pallets", "order", "trip", "step", "distance", "transportType", "city", "Step")
                        .collect(Collectors.toSet()));
        droolsWrapper.setIncludedOutputRules(List.of("CalculateTotal"));
        droolsWrapper.generateOutputCandidates(true);
        droolsWrapper.selectOutputIndicesFromCandidates(List.of(4));

        List<Output> goal = List.of(
                new Output("rulebases.cost.CostCalculationRequest.numPallets", Type.NUMBER, new Value(500), 0.0d)
        );
        TestUtils.runSHAP(droolsWrapper, 0.);
    }

    @Test
    public void palletCountCF() throws ExecutionException, InterruptedException, TimeoutException {
        Supplier<List<Object>> objectSupplier = () -> {
            Trip trip = TestUtils.getDefaultTrip();
            Order order = TestUtils.getDefaultOrder();
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

        // setup Output extraction
        droolsWrapper.setExcludedOutputObjects(
                Stream.of("pallets", "LeftToDistribute", "cost.Product", "cost.OrderLine", "java.lang.Double", "costElements", "Pallet", "City", "Step", "org.drools.core.reteoo.InitialFactImpl", "java.util.ArrayList")
                        .collect(Collectors.toSet()));
        droolsWrapper.setExcludedOutputFields(
                Stream.of("pallets", "order", "trip", "step", "distance", "transportType", "city", "Step")
                        .collect(Collectors.toSet()));
        droolsWrapper.setIncludedOutputRules(List.of("CalculateTotal"));
        droolsWrapper.generateOutputCandidates(true);
        droolsWrapper.selectOutputIndicesFromCandidates(List.of(4));

        List<Output> goal = List.of(
                new Output("rulebases.cost.CostCalculationRequest.numPallets", Type.NUMBER, new Value(500), 0.0d)
        );
        TestUtils.runCounterfactualSearch(droolsWrapper, goal, 60L);
    }
}
