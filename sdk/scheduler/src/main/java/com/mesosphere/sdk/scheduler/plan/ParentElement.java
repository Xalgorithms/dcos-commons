package com.mesosphere.sdk.scheduler.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.protobuf.TextFormat;
import com.mesosphere.sdk.scheduler.plan.strategy.Strategy;
import org.apache.commons.collections.CollectionUtils;
import org.apache.mesos.Protos;

import static com.mesosphere.sdk.config.YAMLConfigurationLoader.LOGGER;
import static com.mesosphere.sdk.scheduler.plan.PlanUtils.allHaveStatus;
import static com.mesosphere.sdk.scheduler.plan.PlanUtils.anyHaveStatus;

/**
 * A type of {@link Element} which itself is a collection of child {@link Element}s.
 *
 * @param <C> the type of the child elements
 */
public interface ParentElement<C extends Element> extends Element, Interruptible {

    /**
     * Gets the children of this Element.
     */
    List<C> getChildren();

    /**
     * Gets the {@link Strategy} applied to the deployment of this Element's children.
     */
    Strategy<C> getStrategy();

    @Override
    default void interrupt() {
        getStrategy().interrupt();
    }

    @Override
    default void proceed() {
        getStrategy().proceed();
    }

    default boolean isInterrupted() {
        return getStrategy().isInterrupted();
    }

    @Override
    default void updateParameters(Map<String, String> parameters) {
        for (C child : getChildren()) {
            child.updateParameters(parameters);
        }
    }

    @Override
    default boolean isEligible(Collection<String> dirtyAssets) {
        return Element.super.isEligible(dirtyAssets) && !isInterrupted();
    }

    /** Updates children */
    @Override
    default void update(Protos.TaskStatus taskStatus) {
        Collection<? extends Element> children = getChildren();
        LOGGER.info("Updated {} with TaskStatus: {}", getName(), TextFormat.shortDebugString(taskStatus));
        children.forEach(element -> element.update(taskStatus));
    }

    /** Restarts children. */
    @Override
    default void restart() {
        Collection<? extends Element> children = getChildren();
        LOGGER.info("Restarting elements within {}: {}", getName(), children);
        children.forEach(element -> element.restart());
    }

    /** Force completes children. */
    @Override
    default void forceComplete() {
        Collection<? extends Element> children = getChildren();
        LOGGER.info("Forcing completion of elements within {}: {}", getName(), children);
        children.forEach(element -> element.forceComplete());
    }

    /**
     * Returns all errors from this {@Link Element} and all its children.
     *
     * @param parentErrors Errors from this {@Link Element} itself.
     * @return a combined list of all errors from the parent and all its children.
     */
    default List<String> getErrors(List<String> parentErrors) {
        List<String> errors = new ArrayList<>();
        errors.addAll(parentErrors);
        Collection<? extends Element> children = getChildren();
        children.forEach(element -> errors.addAll(element.getErrors()));
        return errors;
    }

    @Override
    default Status getStatus() {
        // Ordering matters throughout this method.  Modify with care.
        // Also note that this function MUST NOT call parent.getStatus() as that creates a circular call.

        final Collection<? extends Element> children = getChildren();
        if (children == null) {
            LOGGER.error("Parent element returned null list of children: {}", getName());
            return Status.ERROR;
        }

        Status result;
        if (!getErrors().isEmpty()) {
            result = Status.ERROR;
            LOGGER.debug("({} status={}) Elements contains errors", getName(), result);
        } else if (CollectionUtils.isEmpty(children)) {
            result = Status.COMPLETE;
            LOGGER.debug("({} status={}) Empty collection of elements encountered.", getName(), result);
        } else if (allHaveStatus(Status.COMPLETE, children)) {
            result = Status.COMPLETE;
            LOGGER.debug("({} status={}) All elements have status: {}",
                    getName(), result, Status.COMPLETE);
        } else if (isInterrupted()) {
            result = Status.WAITING;
            LOGGER.info("({} status={}) Parent element is interrupted", getName(), result);
        } else if (anyHaveStatus(Status.WAITING, children)) {
            result = Status.WAITING;
            LOGGER.debug("({} status={}) At least one element has status: {}",
                    getName(), result, Status.WAITING);
        } else if (allHaveStatus(Status.PENDING, children)) {
            result = Status.PENDING;
            LOGGER.debug("({} status={}) All elements have status: {}",
                    getName(), result, Status.PENDING);
        } else if (anyHaveStatus(Status.PREPARED, children)) {
            result = Status.IN_PROGRESS;
            LOGGER.debug("({} status={}) At least one phase has status: {}",
                    getName(), result, Status.PREPARED);
        } else if (anyHaveStatus(Status.IN_PROGRESS, children)) {
            result = Status.IN_PROGRESS;
            LOGGER.debug("({} status={}) At least one phase has status: {}",
                    getName(), result, Status.IN_PROGRESS);
        } else if (anyHaveStatus(Status.COMPLETE, children) && (anyHaveStatus(Status.PENDING, children))) {
            result = Status.IN_PROGRESS;
            LOGGER.debug("({} status={}) At least one element has status '{}' and one has status '{}'",
                    getName(), result, Status.COMPLETE, Status.PENDING);
        } else if (anyHaveStatus(Status.STARTING, children)) {
            result = Status.STARTING;
            LOGGER.debug("({} status={}) At least one element has status '{}'",
                    getName(), result, Status.STARTING);
        } else {
            result = Status.ERROR;
            LOGGER.warn("({} status={}) Unexpected state. children: {}",
                    getName(), result, children);
        }

        return result;
    }
}