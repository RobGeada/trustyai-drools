package drools_integrators;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static drools_integrators.Utils.graphCount;

public class GraphNode {
    private final String type;
    private final int id;
    private final String field;
    private RuleContext ruleContext;
    private Integer[] calls = {0, 0};

    private Object value;
    private Set<Integer> inputNumbers = new HashSet<>();
    private boolean finalized = false;


    public GraphNode(String type, RuleContext ruleContext, int id) {
        this.type = type;
        this.ruleContext = ruleContext;
        this.id = id;
        this.calls[ruleContext.getInputNumber()] = 1;
        inputNumbers.add(ruleContext.getInputNumber());
        this.value = null;
        this.field = "";
    }

    public GraphNode(String type, RuleContext ruleContext, int id, String field, Object value) {
        this.type = type;
        this.ruleContext = ruleContext;
        this.id = id;
        this.calls[ruleContext.getInputNumber()] = 1;
        inputNumbers.add(ruleContext.getInputNumber());
        this.value = value;
        this.field = field;
    }

    public static GraphNode nodeAdd(Graph<GraphNode, DefaultEdge> graph, Map<Integer, GraphNode> nodeMap, GraphNode n){
        boolean inGraph = nodeMap.containsKey(n.hashCode());
        Boolean matchingRuleFlow;
        Boolean matchingValue;

        if (inGraph){
            GraphNode containedNode = nodeMap.get(n.hashCode());
            matchingRuleFlow = containedNode.getRuleContext().getInputNumber() == n.getRuleContext().getInputNumber();
            matchingValue = Objects.equals(containedNode.getValue(), n.getValue());
            // node merge?
            if (matchingRuleFlow || matchingValue) {
                containedNode.getCalls()[n.getRuleContext().getInputNumber()] += 1;
                containedNode.setValue(n.getValue());
                return containedNode;
            }
        }
        graph.addVertex(n);
        nodeMap.put(n.hashCode(), n);
        return n;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GraphNode graphNode = (GraphNode) o;
        if (this.type.contains("Terminal")) {
            return id == graphNode.id
                    && Objects.equals(type, graphNode.type)
                    && (Objects.equals(ruleContext.getInputNumber(), graphNode.ruleContext.getInputNumber()) || Objects.equals(value, graphNode.value))
                    && Objects.equals(field, graphNode.field)
                    && Objects.equals(ruleContext.getRule().getName(), graphNode.ruleContext.getRule().getName());
        } else {
            return id == graphNode.id
                    && Objects.equals(type, graphNode.type)
                    && Objects.equals(ruleContext.getRule().getName(), graphNode.ruleContext.getRule().getName());
        }
    }

    @Override
    public int hashCode() {
        if (this.finalized) {
            return Objects.hash(type, id, ruleContext.getInputNumber(), ruleContext.getRule(), field);
        } else if (this.type.contains("Terminal")) {
            return Objects.hash(type, id, ruleContext.getRule(), field);
        } else {
            return Objects.hash(type);
        }
    }

    @Override
    public String toString() {
        if (this.value == null) {
            return String.format("%s%n%d-%d (%d)", this.type, this.calls[0], this.calls[1], this.hashCode());
        } else {
            return String.format("%s%n%s=%s (%d)", this.type, this.field, this.value, this.hashCode());
        }
    }

    public String getColor(boolean edge) {
        if ((this.calls[0] == 0 && this.calls[1] == 0) || !(this.type.contains("Terminal") || edge)) {
            return "white";
        } else {
            float total = this.calls[0] + this.calls[1];
            String red = String.format("%02X", (int) ((this.calls[0] / total) * 255));
            String blue = String.format("%02X", (int) ((this.calls[1] / total) * 255));
            String result = String.format("#%s00%s", red, blue);
            return result;
        }
    }

    public String getShape() {
        if (this.type.contains("Alpha") || this.type.contains("Terminal")) {
            return "rectangle";
        } else {
            return "oval";
        }
    }

    public String getType() {
        return type;
    }

    public int getId() {
        return id;
    }

    public RuleContext getRuleContext() {
        return ruleContext;
    }

    public void setRuleContext(RuleContext ruleContext) {
        this.ruleContext = ruleContext;
    }

    public Integer[] getCalls() {
        return calls;
    }

    public void setCalls(Integer[] calls) {
        this.calls = calls;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getField() {
        return field;
    }


    public Set<Integer> getInputNumbers() {
        return inputNumbers;
    }

    public void setInputNumbers(Set<Integer> inputNumbers) {
        this.inputNumbers = inputNumbers;
    }

    public boolean isFinalized() {
        return finalized;
    }

    public void setFinalized(boolean finalized) {
        this.finalized = finalized;
    }
}
