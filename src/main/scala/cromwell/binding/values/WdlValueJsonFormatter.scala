package cromwell.binding.values

import spray.json._

object WdlValueJsonFormatter extends DefaultJsonProtocol {
  implicit object WdlValueJsonFormat extends RootJsonFormat[WdlValue] {
    def write(value: WdlValue) = value match {
      case s:WdlString => JsString(s.value)
      case i:WdlInteger => JsNumber(i.value)
      case f:WdlFloat => JsNumber(f.value)
      case b:WdlBoolean => JsBoolean(b.value)
      case f:WdlFile => JsString(f.value)
      case o:WdlObject => JsObject()
      case a:WdlArray => new JsArray(a.value.map(write).toVector)
    }
    def read(value: JsValue) = ???
  }
}
