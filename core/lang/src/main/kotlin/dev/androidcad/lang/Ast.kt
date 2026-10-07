package dev.androidcad.lang

internal sealed interface Expr
internal data class NumLit(val v: Double) : Expr
internal data class StrLit(val v: String) : Expr
internal data class BoolLit(val v: Boolean) : Expr
internal data object UndefLit : Expr
internal data class Ident(val name: String, val line: Int) : Expr
internal data class RangeExpr(val start: Expr, val step: Expr?, val end: Expr) : Expr
internal data class ListExpr(val elems: List<ListElem>) : Expr
internal data class Unary(val op: T, val e: Expr) : Expr
internal data class Binary(val op: T, val l: Expr, val r: Expr, val line: Int) : Expr
internal data class Ternary(val c: Expr, val a: Expr, val b: Expr) : Expr
internal data class Call(val name: String, val args: List<Arg>, val line: Int) : Expr
internal data class Index(val e: Expr, val idx: Expr) : Expr
internal data class Member(val e: Expr, val name: String) : Expr
internal data class LetExpr(val assigns: List<Assignment>, val body: Expr) : Expr

internal data class Arg(val name: String?, val value: Expr)
internal data class Assignment(val name: String, val value: Expr)
internal data class Param(val name: String, val default: Expr?)

internal sealed interface ListElem
internal data class SingleElem(val e: Expr) : ListElem
internal data class EachElem(val e: ListElem) : ListElem
internal data class ForElem(val assigns: List<Assignment>, val body: ListElem) : ListElem
internal data class IfElem(val cond: Expr, val then: ListElem, val otherwise: ListElem?) : ListElem
internal data class LetElem(val assigns: List<Assignment>, val body: ListElem) : ListElem

internal sealed interface Stmt
internal data object EmptyStmt : Stmt
internal data class AssignStmt(val name: String, val value: Expr, val line: Int) : Stmt
internal data class BlockStmt(val body: List<Stmt>) : Stmt
internal data class ModuleDef(val name: String, val params: List<Param>, val body: Stmt) : Stmt
internal data class FunctionDef(val name: String, val params: List<Param>, val body: Expr) : Stmt
internal data class ForStmt(val assigns: List<Assignment>, val body: Stmt) : Stmt
internal data class IfStmt(val cond: Expr, val then: Stmt, val otherwise: Stmt?) : Stmt
internal data class LetStmt(val assigns: List<Assignment>, val body: Stmt) : Stmt
internal data class IncludeStmt(val path: String, val isUse: Boolean, val line: Int) : Stmt

/** Modul-Aufruf; [modifier]: `'#'`, `'%'`, `'!'`, `'*'` oder `null`. */
internal data class ModuleCall(
    val name: String,
    val args: List<Arg>,
    val children: List<Stmt>,
    val modifier: Char?,
    val line: Int,
) : Stmt
