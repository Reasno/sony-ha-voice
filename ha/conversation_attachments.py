"""Authenticated image input for an existing Local OpenAI conversation."""

from __future__ import annotations

import asyncio
import base64
from contextvars import ContextVar
from dataclasses import dataclass, field
from io import BytesIO
from typing import Any

import probatio
from PIL import Image

from homeassistant.components import conversation, websocket_api
from homeassistant.core import HomeAssistant

COMMAND = "local_openai/conversation/process"
MAX_IMAGE_BYTES = 2 * 1024 * 1024
MAX_IMAGE_PIXELS = 3840 * 2160


@dataclass(frozen=True)
class ImageUserContent(conversation.UserContent):
    """Keep image data in the chat session without exposing it in diagnostics."""

    image_url: str = field(default="", repr=False)

    def as_dict(self) -> dict[str, Any]:
        result = super().as_dict()
        result["attachments"] = [{"mime_type": "image/jpeg"}]
        return result


@dataclass(frozen=True)
class ImageInput:
    agent_id: str
    text: str
    image_url: str = field(repr=False)


_current_input: ContextVar[ImageInput | None] = ContextVar(
    "local_openai_image_input", default=None
)


def attach_current_input(
    agent_id: str,
    user_input: conversation.ConversationInput,
    chat_log: conversation.ChatLog,
) -> None:
    """Attach the image to this request's user message exactly once."""
    image_input = _current_input.get()
    if image_input is None or image_input.agent_id != agent_id:
        return
    _current_input.set(None)
    content = chat_log.content[-1]
    if (
        not isinstance(content, conversation.UserContent)
        or content.content != image_input.text
        or user_input.text != image_input.text
    ):
        raise ValueError("Image input does not match the current user message")
    chat_log.content[-1] = ImageUserContent(
        content=content.content, image_url=image_input.image_url
    )


def _image_url(encoded: str) -> str:
    """Validate and normalize one bounded image; discard source metadata."""
    if len(encoded) > ((MAX_IMAGE_BYTES + 2) // 3) * 4:
        raise ValueError("Image exceeds 2 MB")
    raw = base64.b64decode(encoded, validate=True)
    if not raw or len(raw) > MAX_IMAGE_BYTES:
        raise ValueError("Image exceeds 2 MB or is empty")
    with Image.open(BytesIO(raw)) as image:
        if image.format not in ("PNG", "JPEG"):
            raise ValueError("Only PNG and JPEG images are supported")
        if image.width * image.height > MAX_IMAGE_PIXELS:
            raise ValueError("Image dimensions exceed the limit")
        image.thumbnail((1280, 1280), Image.Resampling.LANCZOS)
        output = BytesIO()
        image.convert("RGB").save(output, format="JPEG", quality=85)
    return "data:image/jpeg;base64," + base64.b64encode(output.getvalue()).decode()


@websocket_api.websocket_command(
    {
        "type": COMMAND,
        probatio.Required("agent_id"): str,
        probatio.Required("text"): str,
        probatio.Required("image"): str,
        probatio.Optional("conversation_id"): probatio.Any(str, None),
        probatio.Optional("language"): str,
        probatio.Optional("device_id"): probatio.Any(str, None),
    }
)
@websocket_api.async_response
async def websocket_process(
    hass: HomeAssistant,
    connection: websocket_api.ActiveConnection,
    msg: dict,
) -> None:
    """Process text and image with the same agent, history, and tool policy."""
    # Import lazily to avoid a cycle through the entity's content converter.
    from .conversation import LocalAiConversationEntity

    agent = conversation.async_get_agent(hass, msg["agent_id"])
    if not isinstance(agent, LocalAiConversationEntity):
        connection.send_error(msg["id"], "unsupported_agent", "Select a Local OpenAI agent")
        return
    if not msg["text"].strip() or len(msg["text"]) > 16000:
        connection.send_error(msg["id"], "invalid_text", "Provide a question")
        return
    try:
        image_url = await hass.async_add_executor_job(_image_url, msg["image"])
    except (ValueError, OSError, Image.DecompressionBombError):
        connection.send_error(msg["id"], "invalid_image", "Provide a PNG or JPEG up to 2 MB")
        return

    async def converse() -> conversation.ConversationResult:
        token = _current_input.set(ImageInput(agent.entity_id, msg["text"], image_url))
        try:
            return await conversation.async_converse(
                hass,
                text=msg["text"],
                conversation_id=msg.get("conversation_id"),
                context=connection.context(msg),
                language=msg.get("language"),
                agent_id=msg["agent_id"],
                device_id=msg.get("device_id"),
            )
        finally:
            _current_input.reset(token)

    task = hass.async_create_task(converse(), "Local OpenAI image conversation")
    connection.subscriptions[msg["id"]] = task.cancel
    try:
        result = await asyncio.wait_for(task, timeout=90)
        connection.send_result(msg["id"], result.as_dict())
    except TimeoutError:
        connection.send_error(msg["id"], "conversation_timeout", "Conversation timed out")
    finally:
        connection.subscriptions.pop(msg["id"], None)


def async_register(hass: HomeAssistant) -> None:
    """Register the authenticated conversation image command."""
    websocket_api.async_register_command(hass, websocket_process)
