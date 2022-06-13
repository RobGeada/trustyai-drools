package drools_integrators;

import org.kie.api.definition.rule.Rule;

import java.util.Objects;

public class OutputAccessor {
    public Rule rule;
    public String name;
    public boolean objectExistence;

    public OutputAccessor(Rule rule, String name) {
        this.rule = rule;
        this.name = name;
        this.objectExistence = false;
    }

    public void setObjectExistence(boolean objectExistence){
        this.objectExistence = objectExistence;
    }

    @Override
    public int hashCode() {
        return Objects.hash(rule, name);
    }

    @Override
    public String toString(){
        return this.rule.getName() + ": "+ this.name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OutputAccessor that = (OutputAccessor) o;
        return objectExistence == that.objectExistence && rule.equals(that.rule) && name.equals(that.name);
    }
}
