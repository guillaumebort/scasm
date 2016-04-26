# scasm
## writing a dynamic x86_64 assembler in Scala

This is the final code for my live coding session at **DevoxxFR 2016** I have made with [Criteo Labs](http://labs.criteo.com/). The goal of this presentation was to write a dynamic assembler in Scala; or how to create a Scala function from the final `x86_64` assembly code.

Do not ask for the final goal of this: this is more a learning vehicle to abord several interesting topics.

As an example function I wanted to generate at runtime, I decided to go with the `add: (Int,Int) => Int` function. The assembly code for that is:

```asm
mov rax, rdi
add rax, rsi
ret
```

Which can be read as:
- Move the content of register `rdi` to register `rax`.
- Add the content of register `rsi` to register `rax` and store the result to `rax`.
- Return

Why do I use these specific registers for that? Because I was doing this presentation using MacOS on a 64bits intel laptop. So I have to follow the _System V x86_64_ ABI. If you want to do this on Linux it should be the same. On Windows you probably need to adapt. On 32bits systems it will be more complicated because the parameters are passed on the stack from the begining, so you need more instructions.

Check this about the ABI: http://wiki.osdev.org/System_V_ABI

So the presentation was organized in 2 parts:
- Finding a way to embed the assembly code into Scala.
- Making it executable.

### Embedding assembly into Scala code

Actually this part is pretty easy. After having defined all the required data structures to represent Registers, Operands and Instructions, I have used a custom `StringContext` interpolation, and [Scala parser combinator](https://github.com/scala/scala-parser-combinators).

At the end of this part I was able to write something like:

```scala
val add: Seq[Instr] =
  asm"""
    mov rax, rdi
    add rax, rsi
    ret
  """
```

### Making it executable

The first step is to generate machine code from the asm representation. For that I have just written a minimal assembler supporting the required instructions/access modes.

Useful resources for that:
- http://www.codeproject.com/Articles/662301/x-Instruction-Encoding-Revealed-Bit-Twiddling-fo
- http://wiki.osdev.org/X86-64_Instruction_Encoding
- http://ref.x86asm.net/coder64.html

To check the result of this assembler you can compare with a real existing assembler. For example using `nasm`, I have created this asm file:

```asm
[bits 64]

mov rax, rdi
add rax, rsi
ret
```

And I have compared the output with mine by running:

```
$ nasm add.asm && hexdump add
```

Then I had to load this code in memory and to make it executable. For that I have used `sun.misc.Unsafe` to allocate an aligned page of memory (see the hack to get an aligned page). And then I have used **JNA** to make a wrapper to the libc allowing me to call `int mprotect(void *addr, size_t len, int prot);`.

At this point the code was loaded in memory and marked as executable. I have used **JNA** again to get a native function from the pointer. At the end using Scala implicit conversion I was able to cast it to a proper Scala function type, allowing me to write:

```scala
val add: (Int,Int) => Int = nativeFunction(
  asm"""
    mov rax, rdi
    add rax, rsi
    ret
  """
)

println(add(3,2))
```

A real Scala function created from assembly code => **CHECK**
