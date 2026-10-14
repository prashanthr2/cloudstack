// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.hypervisor.proxmox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class ProxmoxConfigTest {

    @Test
    public void timeoutsDefaultToTheValuesTheClientUsedBeforeTheyWereConfigurable() {
        assertEquals(5, (int) ProxmoxConfig.ApiConnectTimeout.value());
        assertEquals(15, (int) ProxmoxConfig.ApiReadTimeout.value());
    }

    @Test
    public void theRootPasswordIsEmptyAndStoredEncryptedAndSaysWhyItIsNeeded() {
        assertEquals("", ProxmoxConfig.RootPassword.value());
        // "Secure" settings are encrypted in the database
        assertEquals("Secure", ProxmoxConfig.RootPassword.category());
        assertTrue(ProxmoxConfig.RootPassword.description().contains("root@pam"));
        assertTrue(ProxmoxConfig.RootPassword.description().contains("API tokens"));
        assertTrue(ProxmoxConfig.RootPassword.isDynamic());
    }

    @Test
    public void proxmoxTasksMayTakeTenMinutesByDefault() {
        assertEquals(600, (int) ProxmoxConfig.TaskTimeout.value());
    }

    @Test
    public void theTlsCertificateIsNotVerifiedUnlessTheOperatorTurnsThatOn() {
        // consistent with the VMware and XenServer plugins, which accept any server certificate
        assertFalse(ProxmoxConfig.ApiVerifyTls.value());
    }

    @Test
    public void allSettingsAreRegisteredAndCanBeChangedWithoutARestart() {
        ProxmoxConfig config = new ProxmoxConfig();
        assertEquals(5, config.getConfigKeys().length);
        assertTrue(Arrays.asList(config.getConfigKeys()).contains(ProxmoxConfig.ApiConnectTimeout));
        assertTrue(Arrays.asList(config.getConfigKeys()).contains(ProxmoxConfig.ApiReadTimeout));
        assertTrue(Arrays.asList(config.getConfigKeys()).contains(ProxmoxConfig.ApiVerifyTls));
        assertTrue(Arrays.asList(config.getConfigKeys()).contains(ProxmoxConfig.TaskTimeout));
        assertTrue(ProxmoxConfig.TaskTimeout.isDynamic());
        assertTrue(ProxmoxConfig.ApiConnectTimeout.isDynamic());
        assertTrue(ProxmoxConfig.ApiReadTimeout.isDynamic());
        assertTrue(ProxmoxConfig.ApiVerifyTls.isDynamic());
    }
}
