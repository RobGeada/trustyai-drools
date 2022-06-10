package drools_integrators;

import org.kie.api.definition.rule.Rule;

import java.util.Objects;

public class OutputCandidate {
    public Object before;
    public Object after;
    public boolean objectExistence;

    public OutputCandidate(Object before, Object after, boolean objectExistence) {
        this.before = before;
        this.after = after;
        this.objectExistence = objectExistence;
    }

    public OutputCandidate(Object before, Object after) {
        this.before = before;
        this.after = after;
        this.objectExistence = false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(before, after, objectExistence);
    }


}
