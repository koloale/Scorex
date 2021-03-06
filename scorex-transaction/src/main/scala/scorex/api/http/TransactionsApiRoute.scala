package scorex.api.http

import javax.ws.rs.Path

import akka.actor.ActorRefFactory
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import io.swagger.annotations._
import play.api.libs.json.{JsArray, Json}
import scorex.account.Account
import scorex.app.RunnableApplication
import scorex.crypto.encode.Base58
import scorex.transaction.LagonakiState
import scorex.transaction.state.database.blockchain.StoredBlockchain

import scala.util.{Success, Try}

@Path("/transactions")
@Api(value = "/transactions", description = "Information about transactions")
case class TransactionsApiRoute(application: RunnableApplication)(implicit val context: ActorRefFactory)
  extends ApiRoute with CommonApiFunctions {
  val settings = application.settings

  private val state: LagonakiState = application.blockStorage.state

  override lazy val route =
    pathPrefix("transactions") {
      unconfirmed ~ address ~ adressLimit ~ info
    }

  //TODO implement general pagination
  @Path("/address/{address}/limit/{limit}")
  @ApiOperation(value = "Address", notes = "Get list of transactions where specified address has been involved", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "address", value = "Wallet address ", required = true, dataType = "String", paramType = "path"),
    new ApiImplicitParam(name = "limit", value = "Specified number of records to be returned", required = true, dataType = "Long", paramType = "path")
  ))
  def adressLimit: Route = {
    path("address" / Segment / "limit" / IntNumber) { case (address, limit) =>
      getJsonRoute {
        val account = new Account(address)
        val txJsons = state.accountTransactions(account)
          .takeRight(limit)
          .map(_.json)
        JsonResponse(Json.arr(txJsons), StatusCodes.OK)
      }
    }
  }

  @Path("/address/{address}")
  @ApiOperation(value = "Address", notes = "Get list of transactions where specified address has been involved", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "address", value = "Wallet address ", required = true, dataType = "String", paramType = "path")
  ))
  def address: Route = {
    path("address" / Segment) { case address =>
      getJsonRoute {
        val account = new Account(address)
        val txJsons = state.accountTransactions(account).map(_.json)
        JsonResponse(Json.arr(txJsons), StatusCodes.OK)
      }
    }
  }

  @Path("/info/{signature}")
  @ApiOperation(value = "Info", notes = "Get transaction info", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "signature", value = "transaction signature ", required = true, dataType = "String", paramType = "path")
  ))
  def info: Route = {
    path("info" / Segment) { case encoded =>
      getJsonRoute {
        Base58.decode(encoded) match {
          case Success(sig) =>
            state.included(sig, None) match {
              case Some(h) =>
                Try {
                  val block = application.blockStorage.history.asInstanceOf[StoredBlockchain].blockAt(h).get
                  val tx = block.transactions.filter(_.signature sameElements sig).head
                  val json = tx.json + ("height" -> Json.toJson(h))
                  JsonResponse(json, StatusCodes.OK)
                }.getOrElse(JsonResponse(Json.obj("status" -> "error", "details" -> "Internal error"),
                  StatusCodes.InternalServerError))
              case None => JsonResponse(Json.obj("status" -> "error", "details" -> "Transaction is not in blockchain"),
                StatusCodes.NotFound)
            }
          case _ => JsonResponse(Json.obj("status" -> "error", "details" -> "Incorrect signature"),
            StatusCodes.UnprocessableEntity)
        }
      }
    }
  }

  @Path("/unconfirmed")
  @ApiOperation(value = "Unconfirmed", notes = "Get list of unconfirmed transactions", httpMethod = "GET")
  def unconfirmed: Route = {
    path("unconfirmed") {
      getJsonRoute {
        val json = JsArray(application.transactionModule.unconfirmedTxs.map(_.json))
        JsonResponse(json, StatusCodes.OK)
      }
    }
  }

}
