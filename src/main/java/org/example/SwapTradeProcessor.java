package org.example;

import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.QuantityChangeDirectionEnum;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.identifier.AssignedIdentifier;
import cdm.base.staticdata.identifier.Identifier;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.event.common.*;
import cdm.event.workflow.EventInstruction;
import cdm.event.workflow.EventTimestamp;
import cdm.event.workflow.EventTimestampQualificationEnum;
import cdm.event.workflow.WorkflowStep;
import cdm.event.workflow.functions.Create_AcceptedWorkflowStepFromInstruction;
import cdm.product.common.settlement.PriceQuantity;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.regnosys.rosetta.common.postprocess.WorkflowPostProcessor;
import com.rosetta.model.lib.RosettaModelObject;
import com.rosetta.model.lib.RosettaModelObjectBuilder;
import com.rosetta.model.lib.records.Date;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

public class SwapTradeProcessor {

    @Inject
    Create_AcceptedWorkflowStepFromInstruction createWorkflowStep;
    @Inject
    WorkflowPostProcessor postProcessor;

    public WorkflowStep createTerminationEventInstruction(TradeState before,
                                                          Date eventDate,
                                                          BigDecimal notionalDecrease,
                                                          String notionalCurrency) {
        // QuantityChangeInstruction specifying a decrease in notional
        QuantityChangeInstruction quantityChangeInstruction =
                QuantityChangeInstruction.builder()
                        .setDirection(QuantityChangeDirectionEnum.DECREASE)
                        .addChange(PriceQuantity.builder()
                                .addQuantityValue(NonNegativeQuantitySchedule.builder()
                                        .setValue(notionalDecrease)
                                        .setUnit(UnitType.builder().setCurrency(getCurrency(notionalCurrency)))));

        // Create an Instruction that contains:
        // - before TradeState
        // - PrimitiveInstruction containing a QuantityChangeInstruction
        Instruction tradeStateInstruction = Instruction.builder()
                .setBeforeValue(before)
                .setPrimitiveInstruction(PrimitiveInstruction.builder()
                        .setQuantityChange(quantityChangeInstruction));

        // Create a workflow step instruction containing the EventInstruction, EventTimestamp and EventIdentifiers
        return WorkflowStep.builder()
                .setProposedEvent(EventInstruction.builder()
                        .addInstruction(tradeStateInstruction)
                        .setEventDate(eventDate))
                .addTimestamp(EventTimestamp.builder()
                        .setDateTime(ZonedDateTime.of(eventDate.toLocalDate(), LocalTime.of(9, 0), ZoneOffset.UTC.normalized()))
                        .setQualification(EventTimestampQualificationEnum.EVENT_CREATION_DATE_TIME))
                .addEventIdentifier(Identifier.builder()
                        .addAssignedIdentifier(AssignedIdentifier.builder().setIdentifierValue("PartialTermination-Example")))
                .build(); // ensure you call build() on the function input
    }

    public WorkflowStep createNovationInstruction(TradeState beforeTradeState,
                                                  Date eventDate,
                                                  CounterpartyRoleEnum counterpartyRole,
                                                  Party party,
                                                  TradeIdentifier tradeIdentifier, String notionalCurrency) {
        // SplitInstruction contains two split breakdowns
        SplitInstruction splitInstruction = SplitInstruction.builder()
                // Split breakdown for party change, new trade id etc
                .addBreakdown(PrimitiveInstruction.builder()
                        .setPartyChange(PartyChangeInstruction.builder()
                                .setCounterparty(Counterparty.builder()
                                        .setPartyReferenceValue(party)
                                        .setRole(counterpartyRole))
                                .setTradeId(Lists.newArrayList(tradeIdentifier))))
                // Split breakdown to terminate the original trade
                .addBreakdown(PrimitiveInstruction.builder()
                        .setQuantityChange(QuantityChangeInstruction.builder()
                                .setDirection(QuantityChangeDirectionEnum.REPLACE)
                                .addChange(PriceQuantity.builder()
                                        .addQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
                                                .setValue(NonNegativeQuantitySchedule.builder()
                                                        .setValue(BigDecimal.valueOf(0.0))
                                                        .setUnit(UnitType.builder().setCurrency(getCurrency(notionalCurrency))))))));

        // Create an Instruction that contains:
        // - before TradeState
        // - PrimitiveInstruction containing a SplitInstruction
        Instruction tradeStateInstruction = Instruction.builder()
                .setBeforeValue(beforeTradeState)
                .setPrimitiveInstruction(PrimitiveInstruction.builder()
                        .setSplit(splitInstruction));

        // Create a workflow step instruction containing the EventInstruction, EventTimestamp and EventIdentifiers
        return WorkflowStep.builder()
                .setProposedEvent(EventInstruction.builder()
                        .addInstruction(tradeStateInstruction)
                        .setIntent(EventIntentEnum.NOVATION)
                        .setEventDate(eventDate))
                .addTimestamp(EventTimestamp.builder()
                        .setDateTime(ZonedDateTime.of(eventDate.toLocalDate(), LocalTime.of(9, 0), ZoneOffset.UTC.normalized()))
                        .setQualification(EventTimestampQualificationEnum.EVENT_CREATION_DATE_TIME))
                .addEventIdentifier(Identifier.builder()
                        .addAssignedIdentifier(AssignedIdentifier.builder().setIdentifierValue("Novation-Example")))
                .build(); // ensure you call build() on the function input
    }

    /**
     * Invoke function and post-process result (e.g. qualify etc).
     *
     * @param workflowStepInstruction - WorkflowStep containing a proposed EventInstruction
     * @return Qualified WorkflowStep - containing a BusinessEvent
     */
    public WorkflowStep executeEvent(WorkflowStep workflowStepInstruction) {
        // Invoke function to create a fully-specified event WorkflowStep (that contains a BusinessEvent)
        WorkflowStep eventWorkflowStep = createWorkflowStep.evaluate(workflowStepInstruction);

        // Post-process the eventWorkflowStep to qualify, re-resolve references etc.
        // This post-process step is optional depending on how you intend to process the workflow step.
        return postProcess(eventWorkflowStep);
    }

    private <T extends RosettaModelObject> T postProcess(T o) {
        RosettaModelObjectBuilder builder = o.toBuilder();
        postProcessor.postProcess(builder.getType(), builder);
        return (T) builder;
    }

    private FieldWithMetaString getCurrency(String currency) {
        return FieldWithMetaString.builder()
                .setValue(currency)
                .setMeta(MetaFields.builder().setScheme("http://www.fpml.org/coding-scheme/external/iso4217"))
                .build();
    }
}