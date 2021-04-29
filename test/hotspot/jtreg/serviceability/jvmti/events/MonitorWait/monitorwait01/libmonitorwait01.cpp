/*
 * Copyright (c) 2003, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include <stdio.h>
#include <string.h>
#include <jvmti.h>
#include "jvmti_common.h"
#include "jvmti_thread.h"


extern "C" {

/* ========================================================================== */

/* scaffold objects */
static JNIEnv *jni = NULL;
static jvmtiEnv *jvmti = NULL;
static jlong timeout = 0;

/* test objects */
static jthread expected_thread = NULL;
static jobject expected_object = NULL;
static volatile int eventsCount = 0;

/* ========================================================================== */

void JNICALL
MonitorWait(jvmtiEnv *jvmti, JNIEnv *jni, jthread thr, jobject obj, jlong tout) {

  LOG("MonitorWait event:\n\tthread: %p, object: %p, timeout: %d\n", thr, obj, (int) tout);

  print_thread_info(jvmti, jni, thr);

  if (expected_thread == NULL) {
    jni->FatalError("expected_thread is NULL.");
  }

  if (expected_object == NULL) {
    jni->FatalError("expected_object is NULL.");
  }

/* check if event is for tested thread and for tested object */
  if (jni->IsSameObject(expected_thread, thr) &&
      jni->IsSameObject(expected_object, obj)) {
    eventsCount++;
    if (tout != timeout) {
      NSK_COMPLAIN1("Unexpected timeout value: %d\n", (int) tout);
      nsk_jvmti_setFailStatus();
    }
  }
}

/* ========================================================================== */

static int prepare() {
  jvmtiError err;

  /* enable MonitorWait event */
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_MONITOR_WAIT, NULL);
  if (err != JVMTI_ERROR_NONE) {
    LOG("Prepare: 11\n");
    return NSK_FALSE;
  }
  return NSK_TRUE;
}

static int clean() {
  jvmtiError err;
  /* disable MonitorWait event */
  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
                                        JVMTI_EVENT_MONITOR_WAIT,
                                        NULL);
  if (err != JVMTI_ERROR_NONE) {
    nsk_jvmti_setFailStatus();
  }

  jni->DeleteGlobalRef(expected_object);
  jni->DeleteGlobalRef(expected_thread);

  return NSK_TRUE;
}

/* ========================================================================== */

/* agent algorithm
 */
static void JNICALL
agentProc(jvmtiEnv *jvmti, JNIEnv *agentJNI, void *arg) {
  jni = agentJNI;

/* wait for initial sync */
  if (!nsk_jvmti_waitForSync(timeout))
    return;

  if (!prepare()) {
    nsk_jvmti_setFailStatus();
    return;
  }

  /* clear events count */
  eventsCount = 0;

  /* resume debugee to catch MonitorWait event */
  if (!((nsk_jvmti_resumeSync() == NSK_TRUE) && (nsk_jvmti_waitForSync(timeout) ==NSK_TRUE))) {
    return;
  }

  NSK_DISPLAY1("Number of MonitorWait events: %d\n", eventsCount);

  if (eventsCount == 0) {
    NSK_COMPLAIN0("No any MonitorWait event\n");
    nsk_jvmti_setFailStatus();
  }

  if (!clean()) {
    nsk_jvmti_setFailStatus();
    return;
  }

/* resume debugee after last sync */
  if (!nsk_jvmti_resumeSync())
    return;
}

/* ========================================================================== */

/* agent library initialization
 */
#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_monitorwait01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_monitorwait01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_monitorwait01(JavaVM *jvm, char *options, void *reserved) {
    return JNI_VERSION_1_8;
}
#endif
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiCapabilities caps;
  jvmtiEventCallbacks callbacks;
  jvmtiError err;
  jint res;

  timeout = 60000; //TODO fix
  NSK_DISPLAY1("Timeout: %d msc\n", (int) timeout);

  res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_1_1);
  if (res != JNI_OK || jvmti == NULL) {
    LOG("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  err = init_agent_data(jvmti, &agent_data);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  memset(&caps, 0, sizeof(jvmtiCapabilities));
  caps.can_generate_monitor_events = 1;
  caps.can_support_virtual_threads = 1;

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    LOG("(AddCapabilities) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  err = jvmti->GetCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    LOG("(GetCapabilities) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  if (!caps.can_generate_monitor_events) {
    return JNI_ERR;
  }

  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.MonitorWait = &MonitorWait;
  err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  /* register agent proc and arg */
  nsk_jvmti_setAgentProc(agentProc, NULL);

  return JNI_OK;
}

JNIEXPORT void JNICALL Java_monitorwait01_setExpected(JNIEnv *jni, jobject clz, jobject obj, jobject thread) {
  LOG("Remembering global reference for monitor object is %p\n", obj);
  /* make object accessible for a long time */
  expected_object = jni->NewGlobalRef(obj);
  if (expected_object == NULL) {
    jni->FatalError("Error saving global reference to monitor.\n");
  }

  /* make thread accessable for a long time */
  expected_thread = jni->NewGlobalRef(thread);
  if (thread == NULL) {
    jni->FatalError("Error saving global reference to thread.\n");
  }

  return;
}


JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}
}
