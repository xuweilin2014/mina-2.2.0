/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mina.core.session;

/**
 * The session state. A session can be in three different state :
 * <ul>
 *   <li>OPENING : The session has not been fully created</li>
 *   <li>OPENED : The session is opened</li>
 *   <li>CLOSING :  The session is closing</li>
 * </ul>
 * 
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public enum SessionState {
    /**
     * Session being created, not yet completed
     * OPENING 表示当前这个 NioSession 刚刚创建好，但是还没有注册到 selector 上
     */
    OPENING, 
    
    /**
     * Opened session
     * OPENED 表示这个 NioSession 已经创建完毕，并且已经注册到了 selector 上
     */
    OPENED, 
    
    /**
     * A session being closed
     * 每一个 NioSession 中都有一个对应的 java nio 原生的 channel，把这个 channel 注册到 selector 上之后，
     * 会返回一个 SelectionKey 对象，在需要关闭连接时，就会调用 SelectionKey 的 cancel 方法，将其 valid 属性
     * 设置为 false，也同样表明 NioSession 处于 CLOSING 阶段
     */
    CLOSING
}