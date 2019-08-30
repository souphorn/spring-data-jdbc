/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.relational.core.conversion;

import static org.springframework.data.relational.core.conversion.AggregateChange.HandlerFactory.*;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mapping.PersistentPropertyPath;
import org.springframework.data.mapping.TraversalContext;
import org.springframework.data.relational.core.mapping.PersistentPropertyPathExtension;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Represents the change happening to the aggregate (as used in the context of Domain Driven Design) as a whole.
 *
 * @author Jens Schauder
 * @author Mark Paluch
 */
@Getter
public class AggregateChange<T> {

	private final Kind kind;

	/** Type of the aggregate root to be changed */
	private final Class<T> entityType;
	private final List<DbAction<?>> actions = new ArrayList<>();
	/** Aggregate root, to which the change applies, if available */
	@Nullable private T entity;

	static {}

	public AggregateChange(Kind kind, Class<T> entityType, @Nullable T entity) {

		this.kind = kind;
		this.entityType = entityType;
		this.entity = entity;
	}

	static void setIdOfNonRootEntity(RelationalMappingContext context, RelationalConverter converter,
			PersistentPropertyAccessor<?> propertyAccessor, DbAction.WithDependingOn<?> action, Object generatedId) {

		PersistentPropertyPath<RelationalPersistentProperty> propertyPathToEntity = action.getPropertyPath();
		PersistentPropertyPathExtension extPath = new PersistentPropertyPathExtension(context, propertyPathToEntity);

		TraversalContext traversalContext = createTraversalContext(action);

		Object currentPropertyValue = propertyAccessor.getProperty(propertyPathToEntity, traversalContext);
		Assert.notNull(currentPropertyValue, "Trying to set an ID for an element that does not exist");

		if (extPath.hasIdProperty()) {

			RelationalPersistentProperty requiredIdProperty = extPath.getRequiredIdProperty();

			PersistentPropertyPath<RelationalPersistentProperty> pathToId = extPath //
					.extendBy(requiredIdProperty) //
					.getRequiredPersistentPropertyPath();

			Object convertedId = converter.readValue(generatedId, requiredIdProperty.getTypeInformation());
			propertyAccessor.setProperty(pathToId, convertedId, traversalContext);
		}
	}

	private static TraversalContext createTraversalContext(DbAction.WithDependingOn<?> action) {

		TraversalContext traversalContext = new TraversalContext();
		addQualifiers(action, traversalContext);
		return traversalContext;
	}

	private static void addQualifiers(DbAction.WithDependingOn<?> action, TraversalContext traversalContext) {

		Map<PersistentPropertyPath<RelationalPersistentProperty>, Object> qualifiers = action.getQualifiers();

		for (Map.Entry<PersistentPropertyPath<RelationalPersistentProperty>, Object> persistentPropertyPathObjectEntry : qualifiers
				.entrySet()) {

			DbAction.WithEntity<?> parentAction = action.getDependingOn();

			if (parentAction instanceof DbAction.WithDependingOn) {
				addQualifiers((DbAction.WithDependingOn<?>) parentAction, traversalContext);
			}

			RelationalPersistentProperty leafProperty = persistentPropertyPathObjectEntry.getKey().getRequiredLeafProperty();

			HandlerFactory.registerHandlerFor(traversalContext, leafProperty, persistentPropertyPathObjectEntry.getValue());
		}

		RelationalPersistentProperty requiredLeafProperty = action.getPropertyPath().getRequiredLeafProperty();
		if (Set.class.isAssignableFrom(requiredLeafProperty.getRawType())) {

			traversalContext.registerSetHandler( //
					requiredLeafProperty, //
					SET_HANDLER.readHandler(action.getEntity()), //
					SET_HANDLER.writeHandler(action.getEntity()).andThen(o -> (Set) o) //
			);
		}
	}

	public void setEntity(@Nullable T aggregateRoot) {
		entity = aggregateRoot;
	}

	@SuppressWarnings("unchecked")
	public void executeWith(Interpreter interpreter, RelationalMappingContext context, RelationalConverter converter) {

		RelationalPersistentEntity<T> persistentEntity = entity != null
				? (RelationalPersistentEntity<T>) context.getRequiredPersistentEntity(entity.getClass())
				: null;

		PersistentPropertyAccessor<T> propertyAccessor = //
				persistentEntity != null //
						? converter.getPropertyAccessor(persistentEntity, entity) //
						: null;

		actions.forEach(action -> {

			action.executeWith(interpreter);

			processGeneratedId(context, converter, persistentEntity, propertyAccessor, action);
		});

		if (propertyAccessor != null) {
			entity = propertyAccessor.getBean();
		}
	}

	public void addAction(DbAction<?> action) {
		actions.add(action);
	}

	private void processGeneratedId(RelationalMappingContext context, RelationalConverter converter,
			@Nullable RelationalPersistentEntity<T> persistentEntity,
			@Nullable PersistentPropertyAccessor<T> propertyAccessor, DbAction<?> action) {

		if (!(action instanceof DbAction.WithGeneratedId)) {
			return;
		}

		Assert.notNull(persistentEntity,
				"For statements triggering database side id generation a RelationalPersistentEntity must be provided.");
		Assert.notNull(propertyAccessor, "propertyAccessor must not be null");

		Object generatedId = ((DbAction.WithGeneratedId<?>) action).getGeneratedId();

		if (generatedId == null) {
			return;
		}

		if (action instanceof DbAction.InsertRoot && action.getEntityType().equals(entityType)) {
			propertyAccessor.setProperty(persistentEntity.getRequiredIdProperty(), generatedId);
		} else if (action instanceof DbAction.WithDependingOn) {

			setIdOfNonRootEntity(context, converter, propertyAccessor, (DbAction.WithDependingOn<?>) action, generatedId);
		}
	}

	/**
	 * The kind of action to be performed on an aggregate.
	 */
	public enum Kind {
		/**
		 * A {@code SAVE} of an aggregate typically involves an {@code insert} or {@code update} on the aggregate root plus
		 * {@code insert}s, {@code update}s, and {@code delete}s on the other elements of an aggregate.
		 */
		SAVE,

		/**
		 * A {@code DELETE} of an aggregate typically involves a {@code delete} on all contained entities.
		 */
		DELETE
	}

	enum HandlerFactory {

		LIST_HANDLER(List.class) {
			@Override
			Function<Object, Object> readHandler(Object key) {
				return collection -> ((List) collection).get((Integer) key);
			}

			@SuppressWarnings("unchecked")
			@Override
			BiFunction<Object, Object, Object> writeHandler(Object key) {
				return (collection, newValue) -> ((List) collection).set((Integer) key, newValue);
			}
		},

		MAP_HANDLER(Map.class) {
			@Override
			Function<Object, Object> readHandler(Object key) {
				return collection -> ((Map) collection).get(key);
			}

			@SuppressWarnings("unchecked")
			@Override
			BiFunction<Object, Object, Object> writeHandler(Object key) {
				return (collection, newValue) -> ((Map) collection).put(key, newValue);
			}
		},

		SET_HANDLER(Set.class) {
			@Override
			Function<Object, Object> readHandler(Object key) {
				return collection -> key;
			}

			@SuppressWarnings("unchecked")
			@Override
			BiFunction<Object, Object, Object> writeHandler(Object key) {
				return (collection, newValue) -> {
					Set set = (Set) collection;
					if (key != newValue) {
						set.remove(key);
						set.add(newValue);
					}
					return collection;
				};
			}
		};

		final Class<?> handledType;

		abstract Function<Object, Object> readHandler(Object key);

		abstract BiFunction<Object, Object, Object> writeHandler(Object key);

		HandlerFactory(Class handledType) {
			this.handledType = handledType;
		}

		static void registerHandlerFor(TraversalContext context, PersistentProperty property, Object key) {

			Class type = property.getType();
			HandlerFactory handlerFactory = findHandlerFactory(type);

			if (handlerFactory == null) {
				return;
			}
			context.registerHandler(property, handlerFactory.readHandler(key), handlerFactory.writeHandler(key));
		}

		@Nullable
		private static HandlerFactory findHandlerFactory(Class<?> type) {

			for (HandlerFactory factory : HandlerFactory.values()) {

				if (type.isAssignableFrom(factory.handledType)) {
					return factory;
				}
			}
			return null;
		}
	}
}
