package wandou.astore.route

import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterSharding
import akka.pattern.ask
import akka.util.Timeout
import org.apache.avro.Schema
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import spray.http.StatusCodes
import spray.routing.Directives
import wandou.astore.schema.DistributedSchemaBoard
import wandou.astore.schema.PutSchema
import wandou.astore.schema.RemoveSchema
import wandou.astore.script.DistributedScriptBoard
import wandou.astore.script.PutScript
import wandou.astore.script.RemoveScript
import wandou.avpath
import wandou.avpath.Evaluator.Ctx
import wandou.avro
import wandou.avro.ToJson

trait RestRoute { _: spray.routing.Directives =>
  val system: ActorSystem
  def readTimeout: Timeout
  def writeTimeout: Timeout

  def schemaBoard = DistributedSchemaBoard(system).board
  def scriptBoard = DistributedScriptBoard(system).board

  import system.dispatcher

  final def resolver(entityName: String) = ClusterSharding(system).shardRegion(entityName)

  final def restApi = schemaApi ~ accessApi

  final def ping = path("ping") {
    complete("pong")
  }

  final def schemaApi = {
    path("putschema" / Segment / Segment ~ Slash.?) { (entityName, fname) =>
      post {
        entity(as[String]) { schemaStr =>
          try {
            val schemaParser = new Schema.Parser()
            val schema = schemaParser.parse(schemaStr)
            if (schema.getType == Schema.Type.UNION) {
              val entitySchema = schema.getTypes.get(schema.getIndexNamed(fname))
              if (entitySchema != null) {
                complete {
                  schemaBoard.ask(PutSchema(entityName, entitySchema))(writeTimeout).collect {
                    case Success(_)  => StatusCodes.OK
                    case Failure(ex) => StatusCodes.InternalServerError
                  }
                }
              } else {
                complete(StatusCodes.BadRequest)
              }
            } else {
              complete(StatusCodes.BadRequest)
            }

          } catch {
            case ex: Throwable =>
              println(ex) // todo
              complete(StatusCodes.BadRequest)
          }
        }
      }
    } ~ path("putschema" / Segment ~ Slash.?) { entityName =>
      post {
        entity(as[String]) { schemaStr =>
          try {
            val schemaParser = new Schema.Parser()
            val schema = schemaParser.parse(schemaStr)
            complete {
              schemaBoard.ask(PutSchema(entityName, schema))(writeTimeout).collect {
                case Success(_)  => StatusCodes.OK
                case Failure(ex) => StatusCodes.InternalServerError
              }
            }
          } catch {
            case ex: Throwable =>
              println(ex) // todo
              complete(StatusCodes.BadRequest)
          }
        }
      }
    } ~ path("delschema" / Segment ~ Slash.?) { entityName =>
      get {
        complete {
          schemaBoard.ask(RemoveSchema(entityName))(writeTimeout).collect {
            case Success(_)  => StatusCodes.OK
            case Failure(ex) => StatusCodes.InternalServerError
          }
        }
      }
    }
  }

  final def accessApi = {
    pathPrefix(Segment) { entityName =>
      path("get" / Segment / Segment ~ Slash.?) { (id, fieldName) =>
        get {
          complete {
            resolver(entityName).ask(avpath.GetField(id, fieldName))(readTimeout).collect {
              case Ctx(value, schema, _, _) =>
                ToJson.toAvroJsonString(value, schema)
              //avro.avroEncode(value, field.schema) match {
              //  case Success(bytes) => bytes
              //  case _              => Array[Byte]()
              //}
              case _ => ""
            }
          }
        }
      } ~ path("get" / Segment ~ Slash.?) { id =>
        get {
          complete {
            resolver(entityName).ask(avpath.GetRecord(id))(readTimeout).collect {
              case Ctx(value, schema, _, _) =>
                ToJson.toAvroJsonString(value, schema)
              //avro.avroEncode(value, schema) match {
              //  case Success(bytes) => bytes
              //  case _              => Array[Byte]()
              //}
              case _ => ""
            }
          }
        }
      } ~ path("put" / Segment / Segment ~ Slash.?) { (id, fieldName) =>
        post {
          entity(as[String]) { json =>
            complete {
              resolver(entityName).ask(avpath.PutFieldJson(id, fieldName, json))(writeTimeout).collect {
                case Success(_)  => StatusCodes.OK
                case Failure(ex) => StatusCodes.InternalServerError
              }
            }
          }
        }
      } ~ path("put" / Segment ~ Slash.?) { id =>
        post {
          entity(as[String]) { json =>
            complete {
              resolver(entityName).ask(avpath.PutRecordJson(id, json))(writeTimeout).collect {
                case Success(_)  => StatusCodes.OK
                case Failure(ex) => StatusCodes.InternalServerError
              }
            }
          }
        }
      } ~ path("select" / Segment ~ Slash.?) { id =>
        post {
          entity(as[String]) { body =>
            splitPathAndValue(body) match {
              case List(pathExp, _*) =>
                complete {
                  resolver(entityName).ask(avpath.Select(id, pathExp))(readTimeout).collect {
                    case xs: List[_] =>
                      xs.map {
                        case Ctx(value, schema, _, _) => ToJson.toAvroJsonString(value, schema)
                      } mkString ("[", ",", "]")
                  }
                }
              case _ =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("update" / Segment ~ Slash.?) { id =>
        post {
          entity(as[String]) { body =>
            splitPathAndValue(body) match {
              case List(pathExp, valueJson) =>
                complete {
                  resolver(entityName).ask(avpath.UpdateJson(id, pathExp, valueJson))(writeTimeout).collect {
                    case Success(_)  => StatusCodes.OK
                    case Failure(ex) => StatusCodes.InternalServerError
                  }
                }
              case _ =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("insert" / Segment ~ Slash.?) { id =>
        post {
          entity(as[String]) { body =>
            splitPathAndValue(body) match {
              case List(pathExp, json) =>
                complete {
                  resolver(entityName).ask(avpath.InsertJson(id, pathExp, json))(writeTimeout).collect {
                    case Success(_)  => StatusCodes.OK
                    case Failure(ex) => StatusCodes.InternalServerError
                  }
                }
              case _ =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("insertall" / Segment ~ Slash.?) { id =>
        post {
          entity(as[String]) { body =>
            splitPathAndValue(body) match {
              case List(pathExp, json) =>
                complete {
                  resolver(entityName).ask(avpath.InsertAllJson(id, pathExp, json))(writeTimeout).collect {
                    case Success(_)  => StatusCodes.OK
                    case Failure(ex) => StatusCodes.InternalServerError
                  }
                }
              case _ =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("delete" / Segment ~ Slash.?) { id =>
        post {
          entity(as[String]) { body =>
            splitPathAndValue(body) match {
              case List(pathExp, _*) =>
                complete {
                  resolver(entityName).ask(avpath.Delete(id, pathExp))(writeTimeout).collect {
                    case Success(_)  => StatusCodes.OK
                    case Failure(ex) => StatusCodes.InternalServerError
                  }
                }
              case _ =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("clear" / Segment ~ Slash.?) { id =>
        post {
          entity(as[String]) { body =>
            splitPathAndValue(body) match {
              case List(pathExp, _*) =>
                complete {
                  resolver(entityName).ask(avpath.Clear(id, pathExp))(writeTimeout).collect {
                    case Success(_)  => StatusCodes.OK
                    case Failure(ex) => StatusCodes.InternalServerError
                  }
                }
              case _ =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("putscript" / Segment ~ Slash.?) { scriptId =>
        post {
          entity(as[String]) { script =>
            complete {
              scriptBoard.ask(PutScript("Account", scriptId, script))(writeTimeout).collect {
                case Success(_)  => StatusCodes.OK
                case Failure(ex) => StatusCodes.InternalServerError
              }
            }
          }
        }
      } ~ path("delscript" / Segment ~ Slash.?) { scriptId =>
        post {
          entity(as[String]) { script =>
            complete {
              scriptBoard.ask(RemoveScript("Account", scriptId))(writeTimeout).collect {
                case Success(_)  => StatusCodes.OK
                case Failure(ex) => StatusCodes.InternalServerError
              }
            }
          }
        }
      }
    }
  }

  private def splitPathAndValue(body: String): List[String] = {
    val len = body.length
    var i = body.indexOf('\r')
    if (i > 0) {
      if (i + 1 < len && body.charAt(i + 1) == '\n') {
        val pathExp = body.substring(0, i)
        val valueJson = body.substring(i + 2, len)
        List(pathExp, valueJson)
      } else {
        val pathExp = body.substring(0, i)
        val valueJson = body.substring(i + 1, len)
        List(pathExp, valueJson)
      }
    } else {
      i = body.indexOf('\n')
      if (i > 0) {
        val pathExp = body.substring(0, i)
        val valueJson = body.substring(i + 1, len)
        List(pathExp, valueJson)
      } else {
        List(body)
      }
    }
  }

}
