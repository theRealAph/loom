/*
 * Copyright (c) 2003, 2020, Oracle and/or its affiliates. All rights reserved.
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
#include "jni.h"
#include "jni_md.h"
#include "jvmti.h"

#include "jvmti_common.h"

extern "C" {

#define STATUS_FAILED 2
#define PASSED 0


#define METH_NUM 4
static const char *METHODS[][2] = {
    {"bpMethod", "()V"},
    {"bpMethod2", "()I"},
    {"bpMethodV", "()V"},
    {"bpMethod2V", "()I"}
};

static const jboolean METHODS_ATTRS[METH_NUM] = {JNI_FALSE, JNI_FALSE, JNI_TRUE, JNI_TRUE};


static const char *CLASS_SIG = "Lbreakpoint01;";

static const char *THREAD_NAME = "breakpoint01Thr";

static volatile int bpEvents[METH_NUM];
static volatile jint result = PASSED;
static jvmtiEnv *jvmti = NULL;
static jvmtiEventCallbacks callbacks;

static volatile int callbacksEnabled = NSK_TRUE;
static jrawMonitorID agent_lock;

static void initCounters() {
  int i;

  for (i = 0; i < METH_NUM; i++)
    bpEvents[i] = 0;
}

static void setBP(jvmtiEnv *jvmti, JNIEnv *jni, jclass klass) {
  jmethodID mid;
  jvmtiError err;
  int i;

  for (i = 0; i < METH_NUM; i++) {
    mid = jni->GetMethodID(klass, METHODS[i][0], METHODS[i][1]);
    if (mid == nullptr) {
      jni->FatalError("failed to get ID for the java method\n");
    }

    err = jvmti->SetBreakpoint(mid, 0);
    if (err != JVMTI_ERROR_NONE) {
      jni->FatalError("failed to set breakpoint\n");
    }
  }
}

/** callback functions **/
void JNICALL
ClassLoad(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jclass klass) {
  char *sig, *generic;
  jvmtiError err;

  RawMonitorLocker rml(jvmti, jni, agent_lock);
  if (callbacksEnabled) {
    // GetClassSignature may be called only during the start or the live phase
    err = jvmti->GetClassSignature(klass, &sig, &generic);
    if (err != JVMTI_ERROR_NONE) {
      jni->FatalError("failed to obtain a class signature\n");
    }

    if (sig != NULL && (strcmp(sig, CLASS_SIG) == 0)) {
      printf("ClassLoad event received for the class %s setting breakpoints ...\n", sig);
      setBP(jvmti, jni, klass);
    }
  }
}

void JNICALL
Breakpoint(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jmethodID method, jlocation location) {
  jclass klass;
  char *clsSig, *generic, *methNam, *methSig;
  jvmtiThreadInfo thr_info;
  jvmtiError err;
  int checkStatus = PASSED;
  int i;

  printf(">>>> Breakpoint event received\n");

/* checking thread info */
  err = jvmti->GetThreadInfo(thread, &thr_info);
  if (err != JVMTI_ERROR_NONE) {
    result = STATUS_FAILED;
    printf("TEST FAILED: unable to get thread info during Breakpoint callback\n\n");
    return;
  }

  if (thr_info.name == NULL ||
      strcmp(thr_info.name, THREAD_NAME) != 0) {
    result = checkStatus = STATUS_FAILED;
    printf(
        "TEST FAILED: Breakpoint event with unexpected thread info:\n"
        "\tname: \"%s\"\ttype: %s %s thread\n\n",
        (thr_info.name == NULL) ? "NULL" : thr_info.name,
        (jni->IsVirtualThread(thread) == JNI_TRUE) ? "virtual" : "kernel",
        (thr_info.is_daemon == JNI_TRUE) ? "deamon" : "user");
  } else {
    printf("CHECK PASSED: thread name: \"%s\"\ttype: %s %s thread\n",
           thr_info.name,        (jni->IsVirtualThread(thread) == JNI_TRUE) ? "virtual" : "kernel",
        (thr_info.is_daemon == JNI_TRUE) ? "deamon" : "user");
  }


/* checking location */
  if (location != 0) {
    result = checkStatus = STATUS_FAILED;
    printf("TEST FAILED: Breakpoint event with unexpected location %ld:\n\n", (long) location);
  } else {
    printf("CHECK PASSED: location: %ld as expected\n", (long) location);
  }

/* checking method info */
  err = jvmti->GetMethodDeclaringClass(method, &klass);
  if (err != JVMTI_ERROR_NONE) {
    result = checkStatus = STATUS_FAILED;
    printf("TEST FAILED: unable to get method declaring class during Breakpoint callback\n\n");
    return;
  }
  err = jvmti->GetClassSignature(klass, &clsSig, &generic);
  if (err != JVMTI_ERROR_NONE) {
    result = checkStatus = STATUS_FAILED;
    printf("TEST FAILED: unable to obtain a class signature during Breakpoint callback\n\n");
    return;
  }
  if (clsSig == NULL ||
      strcmp(clsSig, CLASS_SIG) != 0) {
    result = checkStatus = STATUS_FAILED;
    printf(
        "TEST FAILED: Breakpoint event with unexpected class signature:\n"
        "\t\"%s\"\n\n",
        (clsSig == NULL) ? "NULL" : clsSig);
  } else {
    printf("CHECK PASSED: class signature: \"%s\"\n", clsSig);
  }

  err = jvmti->GetMethodName(method, &methNam, &methSig, NULL);
  if (err != JVMTI_ERROR_NONE) {
    result = checkStatus = STATUS_FAILED;
    printf("TEST FAILED: unable to get method name during Breakpoint callback\n\n");
    return;
  }

  for (i = 0; i < METH_NUM; i++) {
    if (strcmp(methNam, METHODS[i][0]) == 0 &&
        strcmp(methSig, METHODS[i][1]) == 0) {
      printf("CHECK PASSED: method name: \"%s\"\tsignature: \"%s\" %d\n", methNam, methSig, i);
      jboolean isVirtual = jni->IsVirtualThread(thread);
      if (isVirtual != METHODS_ATTRS[i]) {
        printf("TEST FAILED: IsVirtualThread check failed with unexpected result %d  when expected is %d\n", isVirtual, METHODS_ATTRS[i]);
        result = checkStatus = STATUS_FAILED;
      }
      if (checkStatus == PASSED) {
        bpEvents[i]++;
      }
      break;
    }
  }
  err = jvmti->Deallocate((unsigned char *) methNam);
  if (err != JVMTI_ERROR_NONE) {
    result = STATUS_FAILED;
    printf("TEST FAILED: unable to deallocate memory pointed to method name\n\n");
  }

  err = jvmti->Deallocate((unsigned char *) methSig);
  if (err != JVMTI_ERROR_NONE) {
    result = STATUS_FAILED;
    printf("TEST FAILED: unable to deallocate memory pointed to method signature\n\n");
  }

  printf("<<<<\n\n");
}

void JNICALL
VMStart(jvmtiEnv *jvmti, JNIEnv *jni) {
  RawMonitorLocker rml(jvmti, jni, agent_lock);
  callbacksEnabled = NSK_TRUE;
}

void JNICALL
VMDeath(jvmtiEnv *jvmti, JNIEnv *jni) {
  RawMonitorLocker rml(jvmti, jni, agent_lock);
  callbacksEnabled = NSK_FALSE;
}
/************************/

JNIEXPORT jint JNICALL Java_breakpoint01_check(JNIEnv *jni, jobject obj) {
  int i;

  for (i = 0; i < METH_NUM; i++) {
    if (bpEvents[i] != 1) {
      result = STATUS_FAILED;
      printf(
          "TEST FAILED: wrong number of Breakpoint events\n"
          "\tfor the method \"%s %s\":\n"
          "\t\tgot: %d\texpected: 1\n",
          METHODS[i][0], METHODS[i][1], bpEvents[i]);
    } else {
      printf("CHECK PASSED: %d Breakpoint event(s) for the method \"%s %s\" as expected\n",
             bpEvents[i], METHODS[i][0], METHODS[i][1]);
    }
  }

  return result;
}

jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiCapabilities caps;
  jvmtiError err;
  jint res;

  res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_9);
  if (res != JNI_OK || jvmti == NULL) {
    printf("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  initCounters();

  memset(&caps, 0, sizeof(jvmtiCapabilities));
  caps.can_generate_breakpoint_events = 1;
  caps.can_support_virtual_threads = 1;

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  err = jvmti->GetCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  if (!caps.can_generate_single_step_events)
    printf("Warning: generation of single step events is not implemented\n");

  /* set event callback */
  printf("setting event callbacks ...\n");
  (void) memset(&callbacks, 0, sizeof(callbacks));
  callbacks.ClassLoad = &ClassLoad;
  callbacks.Breakpoint = &Breakpoint;
  callbacks.VMStart = &VMStart;
  callbacks.VMDeath = &VMDeath;

  err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (err != JVMTI_ERROR_NONE)
    return JNI_ERR;

  printf("setting event callbacks done\nenabling JVMTI events ...\n");

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_START, NULL);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_DEATH, NULL);
  if (err != JVMTI_ERROR_NONE)
    return JNI_ERR;
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_LOAD, NULL);
  if (err != JVMTI_ERROR_NONE)
    return JNI_ERR;
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, NULL);
  if (err != JVMTI_ERROR_NONE)
    return JNI_ERR;
  printf("enabling the events done\n\n");

  agent_lock = create_raw_monitor(jvmti, "agent_lock");

  if (agent_lock == NULL) {
    return JNI_ERR;
  }

  return JNI_OK;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}


}