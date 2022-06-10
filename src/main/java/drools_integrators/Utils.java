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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public class Utils {
    // given a map of key and pairs, return the submap of all key,value pairs that satisfy some function f(key)
    public static <K, V> Map<K, V> getMapSlice(Map<K, V> map, Predicate<K> predicate) {
        Map<K, V> output = new HashMap<>();
        for (Map.Entry<K, V> entry : map.entrySet()){
            if (predicate.test(entry.getKey())){
                output.put(entry.getKey(), entry.getValue());
            }
        }
        return output;
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
