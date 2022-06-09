package drools_integrators;

import org.apache.commons.math3.util.Pair;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class Utils {
    /* function to add/merge nodes as necessary
 nodes are MERGED when they already exist in the graph for the same rule or contain the same value (in the case of terminal nodes)
 nodes are ADDED otherwise
 */
    // given a map of maps of pairs, add a Pair to the key $key, subkey $subkey
    // creates the map at $key if necessary
    public static <K,S, V> void addToHashOfHashOfPair(Map<K, Map<S, Pair<V,V>>> map, K key, S subkey, Pair<V,V> value) {
        Map<S, Pair<V,V>> subMap = map.containsKey(key) ? map.get(key) : new HashMap<>();
        if (subMap.containsKey(subkey)){
            Pair<V, V> transitiveObject = new Pair<>(subMap.get(subkey).getFirst(), value.getSecond());
            subMap.put(subkey, transitiveObject);
        } else {
            subMap.put(subkey, value);
        }
        map.put(key, subMap);
    }


    public static void graphCount(Graph<GraphNode, DefaultEdge> graph){
        double[] calls = new double[2];
        for (GraphNode graphNode : graph.vertexSet()){
            calls[0] += graphNode.getCalls()[0];
            calls[1] += graphNode.getCalls()[1];
        }
        System.out.println(Arrays.toString(calls));
    }

    // print out the Rete graph to GraphViz dotfile, then render as pdf (if dot is installed on the system)
    public static void printGraph(Graph<GraphNode, DefaultEdge> graph){
        for (GraphNode g : graph.vertexSet()){
            g.setFinalized(true);
        }
        DOTExporter<GraphNode, DefaultEdge> exporter =
                new DOTExporter<>(v -> Integer.toString(v.hashCode()));
        exporter.setVertexAttributeProvider((v) -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(v.toString()));
            map.put("fillcolor", DefaultAttribute.createAttribute(v.getColor(false)));
            map.put("color", DefaultAttribute.createAttribute(v.getColor(true)));
            map.put("style", DefaultAttribute.createAttribute("filled"));
            map.put("fontcolor", DefaultAttribute.createAttribute(v.getColor(false).equals("white") ? "black": "white"));
            map.put("shape", DefaultAttribute.createAttribute(v.getShape()));
            return map;
        });
        Writer writer = new StringWriter();
        exporter.exportGraph(graph, writer);
        try {
            File newFile = new File("graph.dot");
            FileWriter fileWriter = new FileWriter(newFile);
            fileWriter.write(writer.toString());
            fileWriter.close();
            System.out.printf("Wrote %d-node graph to dotfile.%n", graph.vertexSet().size());
            Path currentRelativePath = Paths.get("");
            String currentAbsolutePath = currentRelativePath.toAbsolutePath().toString();
            System.out.printf("Rendering in %s...", currentAbsolutePath);
            ProcessBuilder pb = new ProcessBuilder("dot", "-Tpdf", "graph.dot", "-o", "graph.pdf");
            pb.inheritIO();
            pb.directory(new File(currentAbsolutePath));
            pb.start();
            System.out.println("[done]");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
