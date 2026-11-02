"""Unit tests for Android Resource and ViewBinding linking."""

import pytest
from pathlib import Path
from analyzer.android_resources import AndroidResourceRegistry, binding_to_layout_name, camel_to_snake


def test_binding_to_layout_name():
    assert binding_to_layout_name("ActivityMainBinding") == "activity_main"
    assert binding_to_layout_name("ItemChatMessageBinding") == "item_chat_message"
    assert binding_to_layout_name("FragmentProfileBinding") == "fragment_profile"
    assert binding_to_layout_name("DialogConfirmDeleteBinding") == "dialog_confirm_delete"


def test_resource_registry_and_view_binding():
    registry = AndroidResourceRegistry()

    # Register resources
    registry.register_resource_file("app/src/main/res/layout/activity_main.xml")
    registry.register_resource_file("app/src/main/res/layout/item_chat.xml")
    registry.register_resource_file("app/src/main/res/drawable/ic_launcher.xml")

    # Layout lookup by binding class
    layout_p = registry.find_layout_by_binding("ActivityMainBinding")
    assert layout_p == "app/src/main/res/layout/activity_main.xml"

    chat_p = registry.find_layout_by_binding("ItemChatBinding")
    assert chat_p == "app/src/main/res/layout/item_chat.xml"

    # R.drawable lookup
    drawables = registry.find_resource("drawable", "ic_launcher")
    assert "app/src/main/res/drawable/ic_launcher.xml" in drawables
