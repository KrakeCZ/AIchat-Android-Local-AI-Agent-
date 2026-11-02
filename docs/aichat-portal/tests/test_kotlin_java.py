"""Unit tests for Kotlin & Java static analysis (Tree-sitter + token fallback)."""

import pytest
from pathlib import Path
from analyzer.kotlin_java import KotlinJavaAnalyzer
from analyzer.model import ConfidenceLevel, EdgeType, Graph, Node, NodeType
from analyzer.symbols import Symbol, SymbolTable


KOTLIN_SAMPLE = b"""
package com.example.ai

import com.example.ai.data.SampleEntity
import com.example.ai.util.DateFormatter as DF
import com.example.ai.network.*

interface Processor {
    fun process(data: SampleEntity): Boolean
}

class AdvancedProcessor(
    private val repo: Repository
) : BaseProcessor(), Processor {

    val targetClass = SampleEntity::class.java

    override fun process(data: SampleEntity): Boolean {
        val helper = Helper()
        return helper.validate(data)
    }
}
"""

ROOM_DAO_SAMPLE = b"""
package com.example.ai.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import com.example.ai.data.SampleEntity

@Dao
interface SampleDao {
    @Query("SELECT * FROM samples")
    fun getAll(): List<SampleEntity>

    @Insert
    fun insert(item: SampleEntity)
}
"""


def test_kotlin_analyzer_ast_and_symbols():
    analyzer = KotlinJavaAnalyzer()
    extracted = analyzer.parse_file("app/src/main/java/com/example/ai/AdvancedProcessor.kt", KOTLIN_SAMPLE)

    assert extracted.package == "com.example.ai"
    import_names = [imp.name for imp in extracted.imports]
    assert "com.example.ai.data.SampleEntity" in import_names

    wildcard_names = [imp.name for imp in extracted.imports if imp.is_wildcard]
    assert any("com.example.ai.network" in w for w in wildcard_names)

    alias_pairs = [(imp.alias, imp.name) for imp in extracted.imports if imp.alias]
    assert ("DF", "com.example.ai.util.DateFormatter") in alias_pairs

    # Symbols defined
    symbol_names = [s.name for s in extracted.symbols]
    assert "Processor" in symbol_names
    assert "AdvancedProcessor" in symbol_names

    # Type references
    ref_names = [r.name for r in extracted.type_references]
    assert any("SampleEntity" in r for r in ref_names)
    assert any("BaseProcessor" in r for r in ref_names)


def test_room_dao_extraction():
    analyzer = KotlinJavaAnalyzer()
    extracted = analyzer.parse_file("app/src/main/java/com/example/ai/db/SampleDao.kt", ROOM_DAO_SAMPLE)

    assert extracted.package == "com.example.ai.db"
    symbol_names = [s.name for s in extracted.symbols]
    assert "SampleDao" in symbol_names

    ref_names = [r.name for r in extracted.type_references]
    assert any("SampleEntity" in r for r in ref_names)
