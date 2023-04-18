/*
 * JBoss, Home of Professional Open Source.
 * Copyright (c) 2022, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.wildfly.test.integration.elytron.oidc.client.propagation.annotation;

import jakarta.annotation.Resource;
import jakarta.ejb.EJB;
import jakarta.ejb.SessionContext;
import jakarta.ejb.Stateless;

import org.jboss.ejb3.annotation.SecurityDomain;

/**
 * Concrete implementation to allow deployment of bean.
 *
 * @author <a href="mailto:fjuma@redhat.com">Farah Juma</a>
 */
@Stateless
@SecurityDomain("ear-servlet-ejb-deployment-remote-same-domain.ear")
public class EntryBeanRemote implements EntryRemote {

    @EJB
    private WhoAmIRemote whoAmIBean;

    @Resource
    private SessionContext context;

    public String whoAmI() {
        return context.getCallerPrincipal().getName();
    }

    public boolean doIHaveRole(String roleName) {
        return context.isCallerInRole(roleName);
    }

}

