/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.intel.analytics.bigdl.ppml.attestation

import org.apache.logging.log4j.LogManager
import scopt.OptionParser

import java.util.Base64

object AttestationCLI {
    def main(args: Array[String]): Unit = {

        val logger = LogManager.getLogger(getClass)
        case class CmdParams(appID: String = "test",
                             appKey: String = "test",
                             asType: String = ATTESTATION_CONVENTION.MODE_EHSM_KMS,
                             asURL: String = "127.0.0.1",
                             userReport: String = "ppml")

        val cmdParser = new OptionParser[CmdParams]("PPML Attestation Quote Generation Cmd tool") {
            opt[String]('i', "appID")
              .text("app id for this app")
              .action((x, c) => c.copy(appID = x))
            opt[String]('k', "appKey")
              .text("app key for this app")
              .action((x, c) => c.copy(appKey = x))
            opt[String]('u', "asURL")
              .text("attestation service url, default is 127.0.0.1")
              .action((x, c) => c.copy(asURL = x))
            opt[String]('t', "asType")
              .text("attestation service type, default is EHSMKeyManagementService")
              .action((x, c) => c.copy(asURL = x))
            opt[String]('p', "userReport")
              .text("userReportDataPath, default is test")
              .action((x, c) => c.copy(userReport = x))

        }
        val params = cmdParser.parse(args, CmdParams()).get

        // Generate quote
        val userReportData = params.userReport
        val quoteGenerator = new GramineQuoteGeneratorImpl()
        val quote = quoteGenerator.getQuote(userReportData.getBytes)

        // Attestation Client
        val as = params.asType match {
            case ATTESTATION_CONVENTION.MODE_EHSM_KMS =>
                new EHSMAttestationService(params.asURL.split(":")(0),
                    params.asURL.split(":")(1), params.appID, params.appKey)
            case ATTESTATION_CONVENTION.MODE_DUMMY =>
                new DummyAttestationService()
            case _ => throw new AttestationRuntimeException("Wrong Attestation service type")
        }
        val attResult = as.attestWithServer(Base64.getEncoder.encodeToString(quote))
        // System.out.print(as.attestWithServer(quote))
        if (attResult._1) {
            System.out.println("Attestation Success!")
            // Bash success
            System.exit(0)
        } else {
            System.out.println("Attestation Fail! Application killed!")
            // bash fail
            System.exit(1)
        }
    }
}