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
package org.springframework.data.relational.core.mapping.event;

import java.util.Optional;

/**
 * an event signalling JDBC processing. It offers access to an {@link Identifier} of the aggregate root affected by the
 * event.
 *
 * @author Oliver Gierke
 */
public interface RelationalEvent {

	/**
	 * The identifier of the aggregate root, triggering this event.
	 *
	 * @return the source of the event as an {@link Identifier}. Guaranteed to be not {@code null}.
	 */
	Identifier getId();

	/**
	 * Returns the aggregate root the event was triggered for.
	 *
	 * @return will never be {@code null}.
	 */
	Optional<Object> getOptionalEntity();

}
