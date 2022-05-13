package drools_integrators;

import org.drools.core.common.InternalFactHandle;
import org.kie.api.definition.rule.Rule;

import java.util.List;

class RuleContext {
    Rule rule;
    int eventHashcode;

    int inputNumber;
    List<InternalFactHandle> factHandles;

    public RuleContext(Rule rule, int eventHashcode, int inputNumber, List<InternalFactHandle> factHandles) {
        this.rule = rule;
        this.eventHashcode = eventHashcode;
        this.inputNumber = inputNumber;
        this.factHandles = factHandles;
    }
}
