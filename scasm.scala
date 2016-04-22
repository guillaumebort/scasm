// WARNING! Run only on MacOS 64 bits

import com.sun.jna._
import scala.util.parsing.combinator._

object Scasm {

  lazy val unsafe = {
    val theUnsafe = classOf[sun.misc.Unsafe].getDeclaredField("theUnsafe")
    theUnsafe.setAccessible(true)
    theUnsafe.get(null).asInstanceOf[sun.misc.Unsafe]
  }

  def lebytes(x: Long) = {
    import java.nio._
    val buffer = ByteBuffer.allocate(java.lang.Long.BYTES)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.putLong(x)
    buffer.array
  }

  val rex = 0x40 + 0x08

  sealed trait Instr {
    def bytes: Seq[Byte]
  }
  case class Mov(to: Operand, from: Operand) extends Instr {
    def bytes = (to, from) match {
      case (RegisterDirect(r1), RegisterDirect(r2)) =>
        Seq(rex, 0x89, 0xC0 + (r2.code << 3) + r1.code).map(_.toByte)
      case (RegisterDirect(r), Immediate(v)) =>
        Seq(rex, 0xb8 + r.code).map(_.toByte) ++ lebytes(v)
      case _ => ???
    }
  }
  case class Add(to: Operand, from: Operand) extends Instr {
    def bytes = (to, from) match {
      case (RegisterDirect(r1), RegisterDirect(r2)) =>
        Seq(rex, 0x01, 0xC0 + (r2.code << 3) + r1.code).map(_.toByte)
      case _ => ???
    }
  }
  case object Syscall extends Instr {
    def bytes = Seq(0x0f, 0x05).map(_.toByte)
  }
  case object Ret extends Instr {
    def bytes = Seq(0xc3).map(_.toByte)
  }

  sealed trait Register {
    def code: Byte
  }
  case object Rax extends Register {
    val code = 0x0.toByte
  }
  case object Rdx extends Register {
    val code = 0x2.toByte
  }
  case object Rsi extends Register {
    val code = 0x6.toByte
  }
  case object Rdi extends Register {
    val code = 0x7.toByte
  }

  sealed trait Operand
  case class RegisterDirect(register: Register) extends Operand
  case class Immediate(value: Long) extends Operand

  object AsmParser extends RegexParsers {
    def register =
      ( "rax" ^^^ Rax
      | "rdx" ^^^ Rdx
      | "rsi" ^^^ Rsi
      | "rdi" ^^^ Rdi
      )

    def directRegister = register ^^ RegisterDirect.apply
    def immediate = "(0x)?[0-9]+".r ^^ { case v => Immediate(java.lang.Long.decode(v)) }
    def operand = directRegister | immediate

    def instruction =
      ( "add" ~> operand ~ "," ~ operand ^^ { case to ~ _ ~ from => Add(to, from) }
      | "mov" ~> operand ~ "," ~ operand ^^ { case to ~ _ ~ from => Mov(to, from) }
      | "syscall" ^^^ Syscall
      | "ret" ^^^ Ret
      )
  }

  implicit class InlineAssembly(val sc: StringContext) {
    def asm(args: Any*): List[Instr] = {
      val code = sc.parts.zipAll(args, "", "").map { case (x,y) => x + y }.mkString

      code
        .split("\n")
        .map(_.trim)
        .filterNot(_.isEmpty)
        .map(AsmParser.parse(AsmParser.instruction, _))
        .collect {
          case AsmParser.Success(instr, _) => instr
        }
        .toList
    }
  }

  implicit class Instructions(val instr: List[Instr]) {
    def dump: String = instr.flatMap(_.bytes).map(b => f"${b & 0xFF}%02x").mkString(" ")
  }

  def allocateAlignedMemoryPage = {
    val pageSize = unsafe.pageSize
    val memory = unsafe.allocateMemory(pageSize * 2)
    (memory + (pageSize - memory % pageSize), pageSize)
  }

  def allocate(str: String) = {
    val bytes = str.getBytes("utf-8")
    val address = unsafe.allocateMemory(bytes.size)
    bytes.zipWithIndex.foreach {
      case (b, i) => unsafe.putByte(address + i, b)
    }
    (address, bytes.size)
  }

  trait Libc extends Library {
    def mprotect(addr: Pointer, len: Long, prot: Int): Int
  }

  object Libc extends Libc {
    private val lib = Native.loadLibrary("c", classOf[Libc]).asInstanceOf[Libc]

    val PROT_NONE = 0
    val PROT_READ = 1
    val PROT_WRITE = 2
    val PROT_EXEC = 4

    def mprotect(addr: Pointer, len: Long, prot: Int) = lib.mprotect(addr, len, prot)
  }

  def nativeFunction(code: Seq[Instr]) = {
    import Libc._
    val (address, size) = allocateAlignedMemoryPage
    assert(
      0 == mprotect(new Pointer(address), size, PROT_READ | PROT_WRITE | PROT_EXEC),
      "mprotect failed!"
    )
    code.flatMap(_.bytes).zipWithIndex.foreach {
      case (b, o) => unsafe.putByte(address + o, b)
    }
    Function.getFunction(new Pointer(address))
  }

  implicit def intFunction1[A](f: Function): A => Int = { (a: A) =>
    f.invokeInt(Array(a.asInstanceOf[AnyRef]))
  }

  implicit def intFunction2[A,B](f: Function): (A,B) => Int = { (a: A, b: B) =>
    f.invokeInt(Array(a.asInstanceOf[AnyRef], b.asInstanceOf[AnyRef]))
  }

  implicit def voidFunction0(f: Function): () => Unit = { () =>
    f.invokeVoid(Array())
  }

  def main(args: Array[String]) {

    val add: (Int,Int) => Int = nativeFunction(
      asm"""
        mov rax, rdi
        add rax, rsi
        ret
      """
    )

    val result = add(5, add(3, 2))
    println(result)

    def adder(m: Int): Int => Int = {
      nativeFunction(
        asm"""
          mov rax, rdi
          mov rsi, $m
          add rax, rsi
          ret
        """
      )
    }

    val add4 = adder(4)
    println(add4(12))

    def yoloPrint(msg: String): () => Unit = nativeFunction {
      val (pointer, size) = allocate(msg)
      asm"""
        mov rax, 0x2000004
        mov rdx, $size
        mov rsi, $pointer
        mov rdi, 1
        syscall
        ret
      """
    }

    val helloWorld = yoloPrint("Hello, DevoxxFR!\n")
    helloWorld()
  }

}
