package rulebases.cost;

import java.util.ArrayList;
import java.util.List;

public class CostCalculationRequest {
    private Order order;

    private Trip trip;

    private List<Pallet> pallets = new ArrayList<Pallet>();
    private List<CostElement> costElements = new ArrayList<CostElement>();

    private double totalTaxCost;
    private double totalHandlingCost;
    private double totalTransportCost;

    public double getTotalCost() {
        return totalCost;
    }

    private double totalCost;

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public int tripChecks = 0;

    public Trip getTrip() {
        tripChecks ++;
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public List<Pallet> getPallets() {
        return pallets;
    }

    public void setPallets(List<Pallet> pallets) {
        this.pallets = pallets;
    }

    public List<CostElement> getCostElements() {
        return costElements;
    }

    public void setCostElements(List<CostElement> costElements) {
        this.costElements = costElements;
    }

    public double getTotalTaxCost() {
        return totalTaxCost;
    }

    public void setTotalTaxCost(double totalTaxCost) {
        this.totalTaxCost = totalTaxCost;
        this.totalCost += totalTaxCost;
    }

    public double getTotalHandlingCost() {
        return totalHandlingCost;
    }

    public void setTotalHandlingCost(double totalHandlingCost) {
        this.totalHandlingCost = totalHandlingCost;
        this.totalCost += totalHandlingCost;
    }

    public double getTotalTransportCost() {
        return totalTransportCost;
    }

    public void setTotalTransportCost(double totalTransportCost) {
        this.totalTransportCost = totalTransportCost;
        this.totalCost += totalTransportCost;
    }

}
