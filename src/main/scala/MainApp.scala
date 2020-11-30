import org.scalajs.dom
import org.scalajs.dom._
import typings.rippleLib.anon.Id
import typings.rippleLib.typesMod.{FormattedSettingsTransaction, Prepare}

import scala.scalajs.js
import typings.rippleLib.apiMod.APIOptions
import typings.rippleLib.errorsMod.RippleError
import typings.rippleLib.ledgerAccountinfoMod.FormattedGetAccountInfoResponse
import typings.rippleLib.mod.RippleAPI
import typings.rippleLib.settingsMod.FormattedSettings
import typings.rippleLib.submitMod.FormattedSubmitResponse

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.JavaScriptException
import scala.util.{Failure, Success}

object MainApp {

  def main(args: Array[String]): Unit = {
    document.addEventListener("DOMContentLoaded", (e: dom.Event) => setupButtonHandler())
  }

  var button: Element = _
  var div: Element    = _

  def setupButtonHandler(): Unit = {
    button = document.getElementById("claim")
    button.addEventListener(
      "click",
      (e: dom.MouseEvent) => {
        button.setAttribute("disabled", "true")
        if (div != null) document.body.removeChild(div)
        div = document.createElement("div")
        document.body.appendChild(div)
        val test     = document.getElementById("testnet").asInstanceOf[html.Input].checked
        val secret   = document.getElementById("secret").asInstanceOf[html.Input].value
        val generate = document.getElementById("yes").asInstanceOf[html.Input].checked
        val address  = if (!generate) Some(document.getElementById("address").asInstanceOf[html.Input].value) else None
        try {
          claimSparks(test, secret, address)
        } catch {
          case e: Throwable =>
            progressMessage(e.getMessage)
            button.removeAttribute("disabled")
        }
      }
    )
  }

  def progressMessage(text: String): Element = {
    println(text)
    val preNode = document.createElement("pre")
    preNode.textContent = text
    div.appendChild(preNode)
    preNode
  }

  def claimSparks(test: Boolean, rippleSecret: String, claimAddress: Option[String]): Unit = {
    val wssUrl = if (test) "wss://s.altnet.rippletest.net:51233" else "wss://s1.ripple.com"
    val api    = new RippleAPI(APIOptions().setServer(wssUrl))
    if (!api.isValidSecret(rippleSecret)) sys.error("The entered ripple secret is not valid")
    val keypair       = api.deriveKeypair(rippleSecret)
    val rippleAddress = api.deriveAddress(keypair.publicKey)
    progressMessage("Ripple Address: " + rippleAddress)

    val sparkAddress = claimAddress.getOrElse {
      progressMessage("Generating Spark Wallet...")
      val sparkWallet = typings.ethereumjsWallet.mod.default.generate()
      val privKey     = sparkWallet.getPrivateKeyString()
      val address     = sparkWallet.getChecksumAddressString()
      progressMessage("Spark Private Key: " + privKey + " <--- SAVE THIS")
      progressMessage("Spark Address: " + address)
      address
    }
    if (!sparkAddress.toLowerCase.matches("0x[0-9a-f]{40}")) sys.error("The entered spark address is not valid")

    val messageKey = "02000000000000000000000000" + sparkAddress.drop(2).toUpperCase

    def dumpAccountInfo(info: FormattedGetAccountInfoResponse) = {
      println("Account Info")
      println("  Sequence: " + info.sequence)
    }
    def dumpAccountSettings(settings: FormattedSettings) = {
      println("Account Settings")
      println("  DisableMasterKey: " + settings.disableMasterKey)
      println("  RegularKey: " + settings.regularKey)
      println("  Signers: " + settings.signers)
    }
    def dumpPreparedTx(prepare: Prepare) = {
      println("Prepared Transaction")
      println("  Txn: " + prepare.txJSON)
      println("  Fee: " + prepare.instructions.fee)
      println("  Sequence: " + prepare.instructions.sequence)
      println("  Max Ledger Version: " + prepare.instructions.maxLedgerVersion)
    }
    def dumpSignedTx(id: Id) = {
      println("Signed Transaction")
      println("  Id: " + id.id)
      println("  Txn: " + id.signedTransaction)
    }
    def dumpSubmitResult(submit: FormattedSubmitResponse) = {
      println("Preliminary Result")
      println("  Code: " + submit.resultCode)
      println("  Message: " + submit.resultMessage)
    }

    progressMessage("Connecting to " + wssUrl + "...")
    val resp = for {
      _ <- api.connect().toFuture
      _ = progressMessage("Retrieving account settings...")
      info <- api.getAccountInfo(rippleAddress).toFuture
      _ = dumpAccountInfo(info)
      _ = progressMessage("Balance: " + info.xrpBalance + " XRP")
      settings <- api.getSettings(rippleAddress).toFuture
      _ = dumpAccountSettings(settings)
      _ = settings.messageKey.fold(progressMessage("MessageKey not set, yet")) { key =>
        if (key == messageKey) sys.error("MessageKey already set to same address")
        else progressMessage("MessageKey set to 0x" + key.replace("02000000000000000000000000", "") + ", replacing...")
      }
      prepare <- api.prepareSettings(rippleAddress, FormattedSettings().setMessageKey(messageKey)).toFuture
      _  = dumpPreparedTx(prepare)
      id = api.sign(prepare.txJSON, rippleSecret)
      _  = dumpSignedTx(id)
      version <- api.getLedgerVersion().toFuture
      _ = println("Current Ledger Version: " + version)
      _ = progressMessage("Submitting claim transaction...")
      submit <- api.submit(id.signedTransaction, true).toFuture
      _ = dumpSubmitResult(submit)
    } yield (id.id, prepare.instructions.maxLedgerVersion, submit.resultCode)

    def verify(id: String, maxLedgerSeq: js.UndefOr[Double], event: js.Any): Unit = {
      val lce = event.asInstanceOf[FormattedLedgerClose]
      println("Ledger Index: " + lce.ledgerVersion)
      api.getTransaction(id).toFuture.onComplete {
        case Success(tx) =>
          val settingsTx = tx.asInstanceOf[FormattedSettingsTransaction]
          if (settingsTx.outcome.result == "tesSUCCESS") {
            progressMessage("Sparks Claimed!")
            val link = document.createElement("a")
            link.setAttribute("href", (if (test) "https://test.bithomp.com/explorer/" else "https://bithomp.com/explorer/") + rippleAddress)
            link.setAttribute("target", "_blank")
            link.textContent = "See in Ripple Explorer"
            div.appendChild(link)
          } else
            progressMessage("Error " + settingsTx.outcome.result)
          api.disconnect()
          button.removeAttribute("disabled")
        case Failure(e) =>
          if (maxLedgerSeq.exists(_ <= lce.ledgerVersion)) {
            progressMessage("Transaction didn't succeed :-(")
            api.disconnect()
            button.removeAttribute("disabled")
          } else {
            progressMessage("Waiting for transaction finality...")
            api.once("ledger", verify(id, maxLedgerSeq, _))
          }
      }
    }

    resp.onComplete {
      case Failure(JavaScriptException(e)) =>
        try {
          progressMessage(e.asInstanceOf[RippleError].data.asInstanceOf[FormattedError].error_message)
          println(e)
        } catch {
          case _: Exception => progressMessage(e.toString)
        }
        api.disconnect()
        button.removeAttribute("disabled")
      case Failure(e) =>
        progressMessage(e.getMessage)
        api.disconnect()
        button.removeAttribute("disabled")
      case Success((id, maxVer, resultCode)) if resultCode == "tesSUCCESS" =>
        api.once("ledger", verify(id, maxVer, _))
      case Success((_, _, resultCode)) =>
        progressMessage("Error " + resultCode)
        api.disconnect()
        button.removeAttribute("disabled")
    }

  }

}

@js.native
trait FormattedLedgerClose extends js.Object {
  var ledgerVersion: Double = js.native
}

@js.native
trait FormattedError extends js.Object {
  var error_message: String = js.native
}
