package drools_integrators;

import org.drools.core.common.InternalFactHandle;
import org.kie.api.definition.rule.Rule;

import java.util.List;

public class RuleContext {
    private final Rule rule;
    private final int eventHashcode;

    private final int inputNumber;
    private final List<InternalFactHandle> factHandles;

    public RuleContext(Rule rule, int eventHashcode, int inputNumber, List<InternalFactHandle> factHandles) {
        this.rule = rule;
        this.eventHashcode = eventHashcode;
        this.inputNumber = inputNumber;
        this.factHandles = factHandles;
    }

    public Rule getRule() {
        return rule;
    }

    public int getEventHashcode() {
        return eventHashcode;
    }

    public int getInputNumber() {
        return inputNumber;
    }

    public List<InternalFactHandle> getFactHandles() {
        return factHandles;
    }
}
