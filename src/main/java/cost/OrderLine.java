package cost;

public class OrderLine {

    private Integer numberItems;
    private Double weight;
    private Product product;


    public OrderLine(int numberItems, Product product) {
        super();
        this.numberItems = numberItems;
        this.product = product;
    }

    public OrderLine(double weight, Product product) {
        super();
        this.weight = weight;
        this.product = product;
    }

    public Integer getNumberItems() {
        return numberItems;
    }

    public void setNumberItems(int numberItems) {
        this.numberItems = numberItems;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public Double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    @Override
    public String toString() {
        return "OrderLine [numberItems=" + numberItems + ", weight=" + weight + ", product=" + product + "]";
    }

}
