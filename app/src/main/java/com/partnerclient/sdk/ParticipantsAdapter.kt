package com.partnerclient.sdk

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.partnerclient.sdkcore.rtc.RtcParticipant

class ParticipantsAdapter : ListAdapter<RtcParticipant, ParticipantsAdapter.ParticipantViewHolder>(ParticipantDiffCallback()) {
    private var hostIdentity: String? = null

    fun setHostIdentity(identity: String?) {
        hostIdentity = identity?.takeIf { it.isNotBlank() }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParticipantViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ParticipantViewHolder(view)
    }

    override fun onBindViewHolder(holder: ParticipantViewHolder, position: Int) {
        holder.bind(getItem(position), hostIdentity)
    }

    class ParticipantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(android.R.id.text1)
        private val identityText: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(participant: RtcParticipant, hostIdentity: String?) {
            val roleTag = if (participant.identity == hostIdentity) "主持人" else "成员"
            val displayName = participant.name.ifBlank { participant.identity }
            nameText.text = "$displayName [$roleTag]"
            identityText.text = buildString {
                append(participant.identity)
                if (participant.isLocal) append(" (我)")
            }
        }
    }

    class ParticipantDiffCallback : DiffUtil.ItemCallback<RtcParticipant>() {
        override fun areItemsTheSame(oldItem: RtcParticipant, newItem: RtcParticipant): Boolean {
            return oldItem.identity == newItem.identity
        }

        override fun areContentsTheSame(oldItem: RtcParticipant, newItem: RtcParticipant): Boolean {
            return oldItem == newItem
        }
    }
}
