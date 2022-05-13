package drools_integrators;

import org.drools.core.common.InternalWorkingMemory;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.kie.kogito.explainability.model.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ParserContext {
    InternalWorkingMemory internalWorkingMemory;
    Map<String, Value> features = new HashMap<>();
    Graph<GraphNode, DefaultEdge> graph;
    HashMap<Integer, GraphNode> graphNodeMap;
    List<GraphNode> previousTerminals = new ArrayList<>();
    List<GraphNode> currentTerminals = new ArrayList<>();

    Set<String> excludedFeatureObjects;

    public ParserContext(InternalWorkingMemory internalWorkingMemory, Map<String, Value> features, Graph<GraphNode, DefaultEdge> graph, Set<String> excludedFeatureObjects) {
        this.internalWorkingMemory = internalWorkingMemory;
        this.features = features;
        this.graph = graph;
        this.excludedFeatureObjects = excludedFeatureObjects;
        this.graphNodeMap = new HashMap<>();
    }

    public ParserContext(InternalWorkingMemory internalWorkingMemory, Map<String, Value> features, Graph<GraphNode, DefaultEdge> graph, HashMap<Integer, GraphNode> graphNodeMap, Set<String> excludedFeatureObjects) {
        this.internalWorkingMemory = internalWorkingMemory;
        this.features = features;
        this.graph = graph;
        this.excludedFeatureObjects = excludedFeatureObjects;
        this.graphNodeMap = graphNodeMap;
    }
}

