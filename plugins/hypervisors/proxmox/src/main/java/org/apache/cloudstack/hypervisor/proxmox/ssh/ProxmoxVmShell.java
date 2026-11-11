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
package org.apache.cloudstack.hypervisor.proxmox.ssh;

import java.nio.file.Path;

/** A login as root in a system VM or router, as little as the patching of the VM needs of it. Closing it ends the login and whatever carries it. */
public interface ProxmoxVmShell extends AutoCloseable {
    /** Runs a command in the VM and returns the result whatever its exit code. */
    ExecResult exec(String command, int timeoutMillis);

    /** Writes a local file into the VM at {@code remotePath}, replacing it; the file is streamed, not read into memory. */
    void upload(Path file, String remotePath, int timeoutMillis);

    @Override
    void close();
}
