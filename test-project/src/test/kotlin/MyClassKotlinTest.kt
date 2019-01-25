/*
 * Copyright (c) 2018, Inversoft Inc., All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

import org.testng.Assert.assertEquals
import org.testng.annotations.Test

/**
 * Test
 *
 * @author Tyler Scott
 */
class MyClassKotlinTest {
    @Test
    fun simple() {
        val thing = KotlinThing()
        assertEquals(thing.doSomething("frank"), "frank did something")
    }

    @Test
    fun dependOnJavaTestFilesAndSources() {
        // Get thing from java test folder
        assertEquals(JavaTestDomainThing().thing, "thing")
        // Get thing from kotlin test folder
        assertEquals(KotlinTestDomainThing().thing, "thing")
        // Get thing from java sources
        assertEquals(JavaThing().thing, "thing")
        // Get thing from kotlin sources
        assertEquals(KotlinThing().thing, "thing")
    }
}
