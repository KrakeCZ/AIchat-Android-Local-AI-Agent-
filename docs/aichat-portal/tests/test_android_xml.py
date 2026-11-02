"""Unit tests for Android XML parsing (AndroidManifest.xml, Layouts, Navigation, Resources)."""

import pytest
from pathlib import Path
from analyzer.android_xml import AndroidXmlAnalyzer, resolve_component_name
from analyzer.model import ConfidenceLevel, EdgeType, Graph, Node, NodeType


MANIFEST_SAMPLE = b"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.ai">

    <application
        android:name=".AiApplication"
        android:label="AI Chat"
        android:icon="@mipmap/ic_launcher"
        android:dataExtractionRules="@xml/data_extraction_rules">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name="com.example.ai.services.FloatingAgentService"
            android:exported="false">
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <receiver
            android:name=".receiver.BootReceiver"
            android:exported="true" />
    </application>
</manifest>
"""

LAYOUT_SAMPLE = b"""<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/bg_panel">

    <include layout="@layout/view_header" />

    <ViewStub
        android:id="@+id/stub_pip"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout="@layout/layout_in_chat_pip" />

    <com.example.ai.ui.custom.CameraGazePreview
        android:id="@+id/cameraPreview"
        android:layout_width="match_parent"
        android:layout_height="wrap_content" />
</LinearLayout>
"""


def test_resolve_component_name():
    assert resolve_component_name(".ui.MainActivity", "com.example.ai") == "com.example.ai.ui.MainActivity"
    assert resolve_component_name("MainActivity", "com.example.ai") == "com.example.ai.MainActivity"
    assert resolve_component_name("com.other.Service", "com.example.ai") == "com.other.Service"


def test_manifest_component_parsing():
    analyzer = AndroidXmlAnalyzer()
    analysis = analyzer.parse_file("app/src/main/AndroidManifest.xml", MANIFEST_SAMPLE, module=":app")

    assert analysis.is_manifest is True
    assert analysis.package == "com.example.ai"

    fqns = {c.resolved_fqn for c in analysis.manifest_components}
    assert "com.example.ai.ui.MainActivity" in fqns
    assert "com.example.ai.AiApplication" in fqns
    assert "com.example.ai.services.FloatingAgentService" in fqns
    assert "com.example.ai.receiver.BootReceiver" in fqns

    # Check resource references in manifest
    res_refs = {(r.res_type, r.res_name) for r in analysis.resource_refs}
    assert ("mipmap", "ic_launcher") in res_refs
    assert ("xml", "data_extraction_rules") in res_refs
    assert ("xml", "accessibility_service_config") in res_refs


def test_layout_custom_view_and_include():
    analyzer = AndroidXmlAnalyzer()
    analysis = analyzer.parse_file("app/src/main/res/layout/activity_main.xml", LAYOUT_SAMPLE, module=":app")

    assert analysis.is_manifest is False
    custom_views = [cv.class_fqn for cv in analysis.custom_views]
    assert "com.example.ai.ui.custom.CameraGazePreview" in custom_views

    includes = [inc.target_layout for inc in analysis.layout_includes]
    assert "view_header" in includes
    assert "layout_in_chat_pip" in includes

    # Check drawables in layout
    drawables = [r.res_name for r in analysis.resource_refs if r.res_type == "drawable"]
    assert "bg_panel" in drawables
