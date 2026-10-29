import SwiftUI

struct ContentView: View {
    @ObservedObject var model: ChatModel
    @State private var draft = ""
    @State private var help = false
    @State private var editingName = false
    @State private var leaving = false
    private let dim = Color(white: 0.7)
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("chitchat / " + (model.page == "chat" ? "conversation" : "nearby")).font(.system(size: 13, design: .monospaced))
                Spacer()
                Button { help = true } label: { Image(systemName: "info.circle").font(.title3).frame(width: 48, height: 48) }.accessibilityLabel("Help and privacy")
            }
            Divider().overlay(Color(white: 0.27))
            if model.page == "home" { home }
            else if model.page == "chat" { conversation }
            else { waiting }
        }
        .padding(.horizontal, 22).padding(.bottom, 12).background(Color.black).foregroundStyle(.white)
        .alert("Same little secret?", isPresented: $model.verification) {
            Button("Codes match") { model.approve() }
            Button("Disconnect", role: .destructive) { model.leave() }
        } message: { Text("Compare this code on both phones:\n\n\(model.code)\n\nOnly tap Match if every character is identical.") }
        .alert("ChitChat", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK") { model.error = nil }
        } message: { Text(model.error ?? "") }
        .confirmationDialog("Leave this room? This phone's messages will disappear.", isPresented: $leaving, titleVisibility: .visible) {
            Button("Leave room", role: .destructive) { model.leave(); draft = "" }
        }
        .sheet(isPresented: $help) { helpView }
        .sheet(isPresented: $editingName) {
            NavigationStack {
                Form { Section("A nickname is enough") { TextField("Sweet Tooth", text: $model.nickname).onChange(of: model.nickname) { model.nickname = String($0.prefix(24)) } } }
                    .navigationTitle("What do we call you?").toolbar { Button("Done") { if model.nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { model.nickname = "Sweet Tooth" }; editingName = false } }
            }.presentationDetents([.medium])
        }
        .onChange(of: model.page) { if $0 == "home" { draft = "" } }
    }
    private func action(_ title: String, filled: Bool = false, perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            Text(title).font(.system(size: 17)).frame(maxWidth: .infinity, minHeight: 62, alignment: .leading).padding(.horizontal, 20)
                .foregroundStyle(filled ? .black : .white).background(filled ? Color.white : .black)
                .clipShape(RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(filled ? .white : Color(white: 0.27)))
        }.buttonStyle(.plain)
    }
    private var home: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("OFFLINE. ON PURPOSE.").font(.system(size: 11, design: .monospaced)).foregroundStyle(dim).padding(.top, 30)
                Text("Small talk.\nZero signal.").font(.system(size: 49, weight: .light)).tracking(-1.5).padding(.top, 22)
                Text("A little closer. A little quieter.\nJust you, your friend, and Bluetooth.").font(.system(size: 16)).lineSpacing(5).foregroundStyle(dim).padding(.top, 14)
                Text("( (  ···  ) )").font(.system(size: 32, design: .monospaced)).frame(maxWidth: .infinity).padding(.vertical, 38)
                action("Open a room                 +", filled: true) { model.start("host") }
                action("Find a friend                   ↗") { model.start("find") }.padding(.top, 12)
                Button { editingName = true } label: { Text("YOU / \(model.nickname)   ✎").font(.system(size: 12, design: .monospaced)).foregroundStyle(dim).padding(.vertical, 28) }
                Divider()
                Text("No account. No internet.\nKeep both apps open while you chat.").font(.system(size: 13)).foregroundStyle(dim).padding(.top, 18)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
    private var waiting: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(model.page == "host" ? "Room for two." : "Who's around?").font(.system(size: 36, weight: .light)).padding(.top, 30)
            Text(model.status).font(.system(size: 16)).foregroundStyle(dim)
            Text("( (  ·  ) )").font(.system(size: 32, design: .monospaced)).padding(.vertical, 14)
            ScrollView { VStack(spacing: 12) { ForEach(model.rooms) { room in action(room.title + "     →") { model.join(room) } } } }
            if model.page == "find" { action("Search again") { model.start("find") } }
            action("Back home") { model.leave() }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private var conversation: some View {
        VStack(spacing: 0) {
            HStack { Text(model.friend).font(.system(size: 26)); Spacer(); Button("Leave") { leaving = true }.padding(16).overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(white: 0.27))) }.padding(.vertical, 16)
            Text("BLUETOOTH · VERIFIED · \(model.code)").font(.system(size: 10, design: .monospaced)).foregroundStyle(dim).frame(maxWidth: .infinity, alignment: .leading).padding(.bottom, 14)
            Divider()
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 16) {
                        Text("Just the two of you. Say something sweet.").font(.system(size: 11, design: .monospaced)).foregroundStyle(dim).padding(.vertical, 20)
                        ForEach(model.messages) { message in
                            HStack {
                                if message.own { Spacer(minLength: 36) }
                                VStack(alignment: message.own ? .trailing : .leading, spacing: 6) {
                                    Text(message.text).font(.system(size: 17)).textSelection(.enabled).padding(.horizontal, 15).padding(.vertical, 12)
                                        .foregroundStyle(message.own ? .black : .white).background(message.own ? Color.white : .black)
                                        .clipShape(RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(message.own ? .white : Color(white: 0.27)))
                                    Text(message.receipt).font(.system(size: 10, design: .monospaced)).foregroundStyle(dim)
                                }
                                if !message.own { Spacer(minLength: 36) }
                            }.id(message.id)
                        }
                    }.padding(.vertical, 12)
                }.onChange(of: model.messages.count) { _ in if let id = model.messages.last?.id { withAnimation { proxy.scrollTo(id, anchor: .bottom) } } }
            }
            HStack(alignment: .bottom, spacing: 10) {
                TextField("A little hello…", text: $draft, axis: .vertical).lineLimit(1...4).padding(14).overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(white: 0.27)))
                Button { if model.sendText(draft) { draft = "" } } label: { Image(systemName: "arrow.up").font(.title2).foregroundStyle(.black).frame(width: 58, height: 58).background(.white).clipShape(RoundedRectangle(cornerRadius: 14)) }.accessibilityLabel("Send message")
            }
            Text("Messages disappear when you leave.").font(.system(size: 10, design: .monospaced)).foregroundStyle(dim).frame(maxWidth: .infinity, alignment: .leading).padding(.top, 8)
        }
    }
    private var helpView: some View {
        NavigationStack {
            ScrollView { Text("1. Both install ChitChat and turn on Bluetooth.\n2. One opens a room; the other finds it.\n3. Compare the code, then both tap Match.\n4. Chat nearby with both apps on screen.\n\nNo internet, account, contacts, or server. Direct Bluetooth only: no mesh, long-distance delivery, photos, or calls.\n\nText is encrypted in transit after verification. Messages stay in memory and disappear when you leave or the app closes. Your keyboard and phone can still retain data.\n\nStart within 1–2 metres. Range varies by phone and obstacles. This version disconnects when backgrounded.\n\nChitChat uses its own protocol and doesn't connect to BitChat.").padding(24) }
                .navigationTitle("A little closer.").toolbar { Button("Done") { help = false } }
        }
    }
}
