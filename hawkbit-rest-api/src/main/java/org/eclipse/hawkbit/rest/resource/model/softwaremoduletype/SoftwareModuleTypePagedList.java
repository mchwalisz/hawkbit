/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.hawkbit.rest.resource.model.softwaremoduletype;

import java.util.List;

import org.eclipse.hawkbit.rest.resource.model.PagedList;

/**
 * Paged list for SoftwareModuleType.
 *
 */
public class SoftwareModuleTypePagedList extends PagedList<SoftwareModuleTypeRest> {

    private final List<SoftwareModuleTypeRest> content;

    /**
     * @param content
     * @param total
     */
    public SoftwareModuleTypePagedList(final List<SoftwareModuleTypeRest> content, final long total) {
        super(content, total);
        this.content = content;
    }

    /**
     * @return the content of the paged list. Never {@code null}.
     */
    public List<SoftwareModuleTypeRest> getContent() {
        return content;
    }

}
